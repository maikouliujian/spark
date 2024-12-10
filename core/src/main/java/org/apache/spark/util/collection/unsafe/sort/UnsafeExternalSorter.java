/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.util.collection.unsafe.sort;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.function.Supplier;

import com.google.common.annotations.VisibleForTesting;
import org.apache.spark.memory.SparkOutOfMemoryError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.TaskContext;
import org.apache.spark.executor.ShuffleWriteMetrics;
import org.apache.spark.memory.MemoryConsumer;
import org.apache.spark.memory.TaskMemoryManager;
import org.apache.spark.memory.TooLargePageException;
import org.apache.spark.serializer.SerializerManager;
import org.apache.spark.storage.BlockManager;
import org.apache.spark.unsafe.Platform;
import org.apache.spark.unsafe.UnsafeAlignedOffset;
import org.apache.spark.unsafe.array.LongArray;
import org.apache.spark.unsafe.memory.MemoryBlock;
import org.apache.spark.util.Utils;

/**
 * External sorter based on {@link UnsafeInMemorySorter}.
 */
//todo 【UnsafeExternalSorter是Spark里面一个非常重要的组件，它可以实现对于大规模的、超过内存大小的数据量的保存、提取和排序。
// 排序算子SortExec使用它来对数据进行排序;SortMergeJoinExec使用它来保存join过程中匹配上的记录】
//todo https://mp.weixin.qq.com/s/erBF_MEWzDaeLji3zgmG6Q
public final class UnsafeExternalSorter extends MemoryConsumer {

  private static final Logger logger = LoggerFactory.getLogger(UnsafeExternalSorter.class);

  @Nullable
  private final PrefixComparator prefixComparator;

  /**
   * {@link RecordComparator} may probably keep the reference to the records they compared last
   * time, so we should not keep a {@link RecordComparator} instance inside
   * {@link UnsafeExternalSorter}, because {@link UnsafeExternalSorter} is referenced by
   * {@link TaskContext} and thus can not be garbage collected until the end of the task.
   */
  @Nullable
  private final Supplier<RecordComparator> recordComparatorSupplier;

  private final TaskMemoryManager taskMemoryManager;
  private final BlockManager blockManager;
  private final SerializerManager serializerManager;
  private final TaskContext taskContext;

  /** The buffer size to use when writing spills using DiskBlockObjectWriter */
  private final int fileBufferSizeBytes;

  /**
   * Force this sorter to spill when there are this many elements in memory.
   */
  //todo 溢写磁盘的阈值
  private final int numElementsForSpillThreshold;

  /**
   * Memory pages that hold the records being sorted. The pages in this list are freed when
   * spilling, although in principle we could recycle these pages across spills (on the other hand,
   * this might not be necessary if we maintained a pool of re-usable pages in the TaskMemoryManager
   * itself).
   */
  //todo allocatedPages里面保存的是输入的数据
  private final LinkedList<MemoryBlock> allocatedPages = new LinkedList<>();
  //todo spillWriters则是当发生spill的时候生成的，每发生一次 spill就会生成一个 spill writer保存到这个 spillWriters 里面。
  private final LinkedList<UnsafeSorterSpillWriter> spillWriters = new LinkedList<>();

  // These variables are reset after spilling:
  //todo inMemSorter是用来排序的。
  @Nullable private volatile UnsafeInMemorySorter inMemSorter;

  private MemoryBlock currentPage = null;
  //todo 内存页的绝对位置
  private long pageCursor = -1;
  private long peakMemoryUsedBytes = 0;
  private long totalSpillBytes = 0L;
  private long totalSortTimeNanos = 0L;
  private volatile SpillableIterator readingIterator = null;

  public static UnsafeExternalSorter createWithExistingInMemorySorter(
      TaskMemoryManager taskMemoryManager,
      BlockManager blockManager,
      SerializerManager serializerManager,
      TaskContext taskContext,
      Supplier<RecordComparator> recordComparatorSupplier,
      PrefixComparator prefixComparator,
      int initialSize,
      long pageSizeBytes,
      int numElementsForSpillThreshold,
      UnsafeInMemorySorter inMemorySorter,
      long existingMemoryConsumption) throws IOException {
    UnsafeExternalSorter sorter = new UnsafeExternalSorter(taskMemoryManager, blockManager,
      serializerManager, taskContext, recordComparatorSupplier, prefixComparator, initialSize,
        pageSizeBytes, numElementsForSpillThreshold, inMemorySorter, false /* ignored */);
    sorter.spill(Long.MAX_VALUE, sorter);
    taskContext.taskMetrics().incMemoryBytesSpilled(existingMemoryConsumption);
    sorter.totalSpillBytes += existingMemoryConsumption;
    // The external sorter will be used to insert records, in-memory sorter is not needed.
    sorter.inMemSorter = null;
    return sorter;
  }

  public static UnsafeExternalSorter create(
      TaskMemoryManager taskMemoryManager,
      BlockManager blockManager,
      SerializerManager serializerManager,
      TaskContext taskContext,
      Supplier<RecordComparator> recordComparatorSupplier,
      PrefixComparator prefixComparator,
      int initialSize,
      long pageSizeBytes,
      int numElementsForSpillThreshold,
      boolean canUseRadixSort) {
    return new UnsafeExternalSorter(taskMemoryManager, blockManager, serializerManager,
      taskContext, recordComparatorSupplier, prefixComparator, initialSize, pageSizeBytes,
      numElementsForSpillThreshold, null, canUseRadixSort);
  }

  private UnsafeExternalSorter(
      TaskMemoryManager taskMemoryManager,
      BlockManager blockManager,
      SerializerManager serializerManager,
      TaskContext taskContext,
      Supplier<RecordComparator> recordComparatorSupplier,
      PrefixComparator prefixComparator,
      int initialSize,
      long pageSizeBytes,
      int numElementsForSpillThreshold,
      @Nullable UnsafeInMemorySorter existingInMemorySorter,
      boolean canUseRadixSort) {
    super(taskMemoryManager, pageSizeBytes, taskMemoryManager.getTungstenMemoryMode());
    this.taskMemoryManager = taskMemoryManager;
    this.blockManager = blockManager;
    this.serializerManager = serializerManager;
    this.taskContext = taskContext;
    this.recordComparatorSupplier = recordComparatorSupplier;
    this.prefixComparator = prefixComparator;
    // Use getSizeAsKb (not bytes) to maintain backwards compatibility for units
    // this.fileBufferSizeBytes = (int) conf.getSizeAsKb("spark.shuffle.file.buffer", "32k") * 1024
    this.fileBufferSizeBytes = 32 * 1024;

    if (existingInMemorySorter == null) {
      RecordComparator comparator = null;
      if (recordComparatorSupplier != null) {
        comparator = recordComparatorSupplier.get();
      }
      this.inMemSorter = new UnsafeInMemorySorter(
        this,
        taskMemoryManager,
        comparator,
        prefixComparator,
        initialSize,
        canUseRadixSort);
    } else {
      this.inMemSorter = existingInMemorySorter;
    }
    this.peakMemoryUsedBytes = getMemoryUsage();
    this.numElementsForSpillThreshold = numElementsForSpillThreshold;

    // Register a cleanup task with TaskContext to ensure that memory is guaranteed to be freed at
    // the end of the task. This is necessary to avoid memory leaks in when the downstream operator
    // does not fully consume the sorter's output (e.g. sort followed by limit).
    taskContext.addTaskCompletionListener(context -> {
      cleanupResources();
    });
  }

  /**
   * Marks the current page as no-more-space-available, and as a result, either allocate a
   * new page or spill when we see the next record.
   */
  @VisibleForTesting
  public void closeCurrentPage() {
    if (currentPage != null) {
      pageCursor = currentPage.getBaseOffset() + currentPage.size();
    }
  }

  /**
   * Sort and spill the current records in response to memory pressure.
   */
  //todo 溢写磁盘
  @Override
  public long spill(long size, MemoryConsumer trigger) throws IOException {
    if (trigger != this) {
      if (readingIterator != null) {
        return readingIterator.spill();
      }
      return 0L; // this should throw exception
    }

    if (inMemSorter == null || inMemSorter.numRecords() <= 0) {
      // There could still be some memory allocated when there are no records in the in-memory
      // sorter. We will not spill it however, to ensure that we can always process at least one
      // record before spilling. See the comments in `allocateMemoryForRecordIfNecessary` for why
      // this is necessary.
      return 0L;
    }

    logger.info("Thread {} spilling sort data of {} to disk ({} {} so far)",
      Thread.currentThread().getId(),
      Utils.bytesToString(getMemoryUsage()),
      spillWriters.size(),
      spillWriters.size() > 1 ? " times" : " time");

    ShuffleWriteMetrics writeMetrics = new ShuffleWriteMetrics();
    //todo 将内存中的数据写入磁盘，每一次溢写磁盘，都新建一个溢写的writer
    final UnsafeSorterSpillWriter spillWriter =
      new UnsafeSorterSpillWriter(blockManager, fileBufferSizeBytes, writeMetrics,
        inMemSorter.numRecords());
    spillWriters.add(spillWriter);
    //todo 溢写磁盘！！！！！！
    spillIterator(inMemSorter.getSortedIterator(), spillWriter);

    final long spillSize = freeMemory();
    // Note that this is more-or-less going to be a multiple of the page size, so wasted space in
    // pages will currently be counted as memory spilled even though that space isn't actually
    // written to disk. This also counts the space needed to store the sorter's pointer array.
    //todo 内存写完直接释放，下次直接写内存
    inMemSorter.freeMemory();
    // Reset the in-memory sorter's pointer array only after freeing up the memory pages holding the
    // records. Otherwise, if the task is over allocated memory, then without freeing the memory
    // pages, we might not be able to get memory for the pointer array.

    taskContext.taskMetrics().incMemoryBytesSpilled(spillSize);
    taskContext.taskMetrics().incDiskBytesSpilled(writeMetrics.bytesWritten());
    totalSpillBytes += spillSize;
    return spillSize;
  }

  /**
   * Return the total memory usage of this sorter, including the data pages and the sorter's pointer
   * array.
   */
  private long getMemoryUsage() {
    long totalPageSize = 0;
    for (MemoryBlock page : allocatedPages) {
      totalPageSize += page.size();
    }
    return ((inMemSorter == null) ? 0 : inMemSorter.getMemoryUsage()) + totalPageSize;
  }

  private void updatePeakMemoryUsed() {
    long mem = getMemoryUsage();
    if (mem > peakMemoryUsedBytes) {
      peakMemoryUsedBytes = mem;
    }
  }

  /**
   * Return the peak memory used so far, in bytes.
   */
  public long getPeakMemoryUsedBytes() {
    updatePeakMemoryUsed();
    return peakMemoryUsedBytes;
  }

  /**
   * @return the total amount of time spent sorting data (in-memory only).
   */
  public long getSortTimeNanos() {
    UnsafeInMemorySorter sorter = inMemSorter;
    if (sorter != null) {
      return sorter.getSortTimeNanos();
    }
    return totalSortTimeNanos;
  }

  /**
   * Return the total number of bytes that has been spilled into disk so far.
   */
  public long getSpillSize() {
    return totalSpillBytes;
  }

  @VisibleForTesting
  public int getNumberOfAllocatedPages() {
    return allocatedPages.size();
  }

  /**
   * Free this sorter's data pages.
   *
   * @return the number of bytes freed.
   */
  private long freeMemory() {
    List<MemoryBlock> pagesToFree = clearAndGetAllocatedPagesToFree();
    long memoryFreed = 0;
    for (MemoryBlock block : pagesToFree) {
      memoryFreed += block.size();
      freePage(block);
    }
    return memoryFreed;
  }

  /**
   * Clear the allocated pages and return the list of allocated pages to let
   * the caller free the page. This is to prevent the deadlock by nested locks
   * if the caller locks the UnsafeExternalSorter and call freePage which locks the
   * TaskMemoryManager and cause nested locks.
   *
   * @return list of allocated pages to free
   */
  private List<MemoryBlock> clearAndGetAllocatedPagesToFree() {
    updatePeakMemoryUsed();
    List<MemoryBlock> pagesToFree = new LinkedList<>(allocatedPages);
    allocatedPages.clear();
    currentPage = null;
    pageCursor = 0;
    return pagesToFree;
  }

  /**
   * Deletes any spill files created by this sorter.
   */
  private void deleteSpillFiles() {
    for (UnsafeSorterSpillWriter spill : spillWriters) {
      File file = spill.getFile();
      if (file != null && file.exists()) {
        if (!file.delete()) {
          logger.error("Was unable to delete spill file {}", file.getAbsolutePath());
        }
      }
    }
  }

  /**
   * Frees this sorter's in-memory data structures and cleans up its spill files.
   */
  public void cleanupResources() {
    // To avoid deadlocks, we can't call methods that lock the TaskMemoryManager
    // (such as various free() methods) while synchronizing on the UnsafeExternalSorter.
    // Instead, we will manipulate UnsafeExternalSorter state inside the synchronized
    // lock and perform the actual free() calls outside it.
    UnsafeInMemorySorter inMemSorterToFree = null;
    List<MemoryBlock> pagesToFree = null;
    try {
      synchronized (this) {
        deleteSpillFiles();
        pagesToFree = clearAndGetAllocatedPagesToFree();
        if (inMemSorter != null) {
          inMemSorterToFree = inMemSorter;
          inMemSorter = null;
        }
      }
    } finally {
      for (MemoryBlock pageToFree : pagesToFree) {
        freePage(pageToFree);
      }
      if (inMemSorterToFree != null) {
        inMemSorterToFree.freeMemory();
      }
    }
  }

  /**
   * Checks whether there is enough space to insert an additional record in to the sort pointer
   * array and grows the array if additional space is required. If the required space cannot be
   * obtained, then the in-memory data will be spilled to disk.
   */
  private void growPointerArrayIfNecessary() throws IOException {
    assert(inMemSorter != null);
    if (!inMemSorter.hasSpaceForAnotherRecord()) {
      if (inMemSorter.numRecords() <= 0) {
        // Spilling was triggered just before this method was called. The pointer array was freed
        // during the spill, so a new pointer array needs to be allocated here.
        LongArray array = allocateArray(inMemSorter.getInitialSize());
        inMemSorter.expandPointerArray(array);
        return;
      }

      long used = inMemSorter.getMemoryUsage();
      LongArray array = null;
      try {
        // could trigger spilling
        array = allocateArray(used / 8 * 2);
      } catch (TooLargePageException e) {
        // The pointer array is too big to fix in a single page, spill.
        spill();
      } catch (SparkOutOfMemoryError e) {
        if (inMemSorter.numRecords() > 0) {
          logger.error("Unable to grow the pointer array");
          throw e;
        }
        // The new array could not be allocated, but that is not an issue as it is longer needed,
        // as all records were spilled.
      }

      if (inMemSorter.numRecords() <= 0) {
        // Spilling was triggered while trying to allocate the new array.
        if (array != null) {
          // We succeeded in allocating the new array, but, since all records were spilled, a
          // smaller array would also suffice.
          freeArray(array);
        }
        // The pointer array was freed during the spill, so a new pointer array needs to be
        // allocated here.
        array = allocateArray(inMemSorter.getInitialSize());
      }
      inMemSorter.expandPointerArray(array);
    }
  }

  /**
   * Allocates an additional page in order to insert an additional record. This will request
   * additional memory from the memory manager and spill if the requested memory can not be
   * obtained.
   *
   * @param required the required space in the data page, in bytes, including space for storing
   *                 the record size.
   */
  private void acquireNewPageIfNecessary(int required) {
    if (currentPage == null ||
      pageCursor + required > currentPage.getBaseOffset() + currentPage.size()) {
      // TODO: try to find space on previous pages
      currentPage = allocatePage(required);
      pageCursor = currentPage.getBaseOffset();
      allocatedPages.add(currentPage);
    }
  }

  /**
   * Allocates more memory in order to insert an additional record. This will request additional
   * memory from the memory manager and spill if the requested memory can not be obtained.
   *
   * @param required the required space in the data page, in bytes, including space for storing
   *                 the record size.
   */
  private void allocateMemoryForRecordIfNecessary(int required) throws IOException {
    // Step 1:
    // Ensure that the pointer array has space for another record. This may cause a spill.
    growPointerArrayIfNecessary();
    // Step 2:
    // Ensure that the last page has space for another record. This may cause a spill.
    acquireNewPageIfNecessary(required);
    // Step 3:
    // The allocation in step 2 could have caused a spill, which would have freed the pointer
    // array allocated in step 1. Therefore we need to check again whether we have to allocate
    // a new pointer array.
    //
    // If the allocation in this step causes a spill event then it will not cause the page
    // allocated in the previous step to be freed. The function `spill` only frees memory if at
    // least one record has been inserted in the in-memory sorter. This will not be the case if
    // we have spilled in the previous step.
    //
    // If we did not spill in the previous step then `growPointerArrayIfNecessary` will be a
    // no-op that does not allocate any memory, and therefore can't cause a spill event.
    //
    // Thus there is no need to call `acquireNewPageIfNecessary` again after this step.
    growPointerArrayIfNecessary();
  }

  /**
   * Write a record to the sorter.
   */
  //todo 插入数据，核心方法！！！！！！
  //todo 在数据插入的时候UnsafeExternalSorter会做两个事情，一是把数据本身保存下来，另外还会构造一个指向这个数据的一个“索引”结构用来优化排序的速度。
  //todo recordBase是说这个内存是由哪个对象所持有的，
  // recordOffset是说要插入的数据在这段内存里面的偏移量，
  // length是要插入的数据的长度，
  // prefix则是可以代表这个插入数据的一个前缀
  public void insertRecord(
      Object recordBase, long recordOffset, int length, long prefix, boolean prefixIsNull)
    throws IOException {

    assert(inMemSorter != null);
    if (inMemSorter.numRecords() >= numElementsForSpillThreshold) {
      logger.info("Spilling data because number of spilledRecords crossed the threshold " +
        numElementsForSpillThreshold);
      //todo 溢写磁盘！！！！！！
      spill();
    }

    final int uaoSize = UnsafeAlignedOffset.getUaoSize();
    // Need 4 or 8 bytes to store the record length.
    final int required = length + uaoSize;
    //todo 申请能够存储recordBase的内存
    allocateMemoryForRecordIfNecessary(required);

    final Object base = currentPage.getBaseObject();
    final long recordAddress = taskMemoryManager.encodePageNumberAndOffset(currentPage, pageCursor);
    UnsafeAlignedOffset.putSize(base, pageCursor, length);
    pageCursor += uaoSize;
    //todo 将recordBase的数据拷贝到base中
    //todo 把内存从入参里面复制到 MemoryBlock 里面去
    Platform.copyMemory(recordBase, recordOffset, base, pageCursor, length);
    pageCursor += length;
    //todo 插入数据指针
    inMemSorter.insertRecord(recordAddress, prefix, prefixIsNull);
  }

  /**
   * Write a key-value record to the sorter. The key and value will be put together in-memory,
   * using the following format:
   *
   * record length (4 bytes), key length (4 bytes), key data, value data
   *
   * record length = key length + value length + 4
   */
  public void insertKVRecord(Object keyBase, long keyOffset, int keyLen,
      Object valueBase, long valueOffset, int valueLen, long prefix, boolean prefixIsNull)
    throws IOException {

    final int uaoSize = UnsafeAlignedOffset.getUaoSize();
    final int required = keyLen + valueLen + (2 * uaoSize);
    allocateMemoryForRecordIfNecessary(required);

    final Object base = currentPage.getBaseObject();
    final long recordAddress = taskMemoryManager.encodePageNumberAndOffset(currentPage, pageCursor);
    UnsafeAlignedOffset.putSize(base, pageCursor, keyLen + valueLen + uaoSize);
    pageCursor += uaoSize;
    UnsafeAlignedOffset.putSize(base, pageCursor, keyLen);
    pageCursor += uaoSize;
    Platform.copyMemory(keyBase, keyOffset, base, pageCursor, keyLen);
    pageCursor += keyLen;
    Platform.copyMemory(valueBase, valueOffset, base, pageCursor, valueLen);
    pageCursor += valueLen;

    assert(inMemSorter != null);
    inMemSorter.insertRecord(recordAddress, prefix, prefixIsNull);
  }

  /**
   * Merges another UnsafeExternalSorters into this one, the other one will be emptied.
   */
  public void merge(UnsafeExternalSorter other) throws IOException {
    other.spill();
    totalSpillBytes += other.totalSpillBytes;
    spillWriters.addAll(other.spillWriters);
    // remove them from `spillWriters`, or the files will be deleted in `cleanupResources`.
    other.spillWriters.clear();
    other.cleanupResources();
  }

  /**
   * Returns a sorted iterator. It is the caller's responsibility to call `cleanupResources()`
   * after consuming this iterator.
   */
  public UnsafeSorterIterator getSortedIterator() throws IOException {
    assert(recordComparatorSupplier != null);
    if (spillWriters.isEmpty()) {
      assert(inMemSorter != null);
      readingIterator = new SpillableIterator(inMemSorter.getSortedIterator());
      return readingIterator;
    } else {
      final UnsafeSorterSpillMerger spillMerger = new UnsafeSorterSpillMerger(
        recordComparatorSupplier.get(), prefixComparator, spillWriters.size());
      for (UnsafeSorterSpillWriter spillWriter : spillWriters) {
        spillMerger.addSpillIfNotEmpty(spillWriter.getReader(serializerManager));
      }
      if (inMemSorter != null) {
        readingIterator = new SpillableIterator(inMemSorter.getSortedIterator());
        spillMerger.addSpillIfNotEmpty(readingIterator);
      }
      return spillMerger.getSortedIterator();
    }
  }

  @VisibleForTesting boolean hasSpaceForAnotherRecord() {
    return inMemSorter.hasSpaceForAnotherRecord();
  }

  private static void spillIterator(UnsafeSorterIterator inMemIterator,
      UnsafeSorterSpillWriter spillWriter) throws IOException {
    while (inMemIterator.hasNext()) {
      inMemIterator.loadNext();
      final Object baseObject = inMemIterator.getBaseObject();
      final long baseOffset = inMemIterator.getBaseOffset();
      final int recordLength = inMemIterator.getRecordLength();
      //todo 将内存中的数据写入到磁盘
      spillWriter.write(baseObject, baseOffset, recordLength, inMemIterator.getKeyPrefix());
    }
    spillWriter.close();
  }

  /**
   * An UnsafeSorterIterator that support spilling.
   */
  class SpillableIterator extends UnsafeSorterIterator {
    private UnsafeSorterIterator upstream;
    private MemoryBlock lastPage = null;
    private boolean loaded = false;
    private int numRecords;

    private Object currentBaseObject;
    private long currentBaseOffset;
    private int currentRecordLength;
    private long currentKeyPrefix;

    SpillableIterator(UnsafeSorterIterator inMemIterator) {
      this.upstream = inMemIterator;
      this.numRecords = inMemIterator.getNumRecords();
    }

    @Override
    public int getNumRecords() {
      return numRecords;
    }

    @Override
    public long getCurrentPageNumber() {
      throw new UnsupportedOperationException();
    }

    public long spill() throws IOException {
      UnsafeInMemorySorter inMemSorterToFree = null;
      List<MemoryBlock> pagesToFree = new LinkedList<>();
      try {
        synchronized (this) {
          if (inMemSorter == null) {
            return 0L;
          }

          long currentPageNumber = upstream.getCurrentPageNumber();

          ShuffleWriteMetrics writeMetrics = new ShuffleWriteMetrics();
          if (numRecords > 0) {
            // Iterate over the records that have not been returned and spill them.
            final UnsafeSorterSpillWriter spillWriter = new UnsafeSorterSpillWriter(
                    blockManager, fileBufferSizeBytes, writeMetrics, numRecords);
            spillIterator(upstream, spillWriter);
            spillWriters.add(spillWriter);
            upstream = spillWriter.getReader(serializerManager);
          } else {
            // Nothing to spill as all records have been read already, but do not return yet, as the
            // memory still has to be freed.
            upstream = null;
          }

          long released = 0L;
          synchronized (UnsafeExternalSorter.this) {
            // release the pages except the one that is used. There can still be a caller that
            // is accessing the current record. We free this page in that caller's next loadNext()
            // call.
            for (MemoryBlock page : allocatedPages) {
              if (!loaded || page.pageNumber != currentPageNumber) {
                released += page.size();
                // Do not free the page, while we are locking `SpillableIterator`. The `freePage`
                // method locks the `TaskMemoryManager`, and it's not a good idea to lock 2 objects
                // in sequence. We may hit dead lock if another thread locks `TaskMemoryManager`
                // and `SpillableIterator` in sequence, which may happen in
                // `TaskMemoryManager.acquireExecutionMemory`.
                pagesToFree.add(page);
              } else {
                lastPage = page;
              }
            }
            allocatedPages.clear();
            if (lastPage != null) {
              // Add the last page back to the list of allocated pages to make sure it gets freed in
              // case loadNext() never gets called again.
              allocatedPages.add(lastPage);
            }
          }

          // in-memory sorter will not be used after spilling
          assert (inMemSorter != null);
          released += inMemSorter.getMemoryUsage();
          totalSortTimeNanos += inMemSorter.getSortTimeNanos();
          // Do not free the sorter while we are locking `SpillableIterator`,
          // as this can cause a deadlock.
          inMemSorterToFree = inMemSorter;
          inMemSorter = null;
          taskContext.taskMetrics().incMemoryBytesSpilled(released);
          taskContext.taskMetrics().incDiskBytesSpilled(writeMetrics.bytesWritten());
          totalSpillBytes += released;
          return released;
        }
      } finally {
        for (MemoryBlock pageToFree : pagesToFree) {
          freePage(pageToFree);
        }
        if (inMemSorterToFree != null) {
          inMemSorterToFree.freeMemory();
        }
      }
    }

    @Override
    public boolean hasNext() {
      return numRecords > 0;
    }

    @Override
    public void loadNext() throws IOException {
      assert upstream != null;
      MemoryBlock pageToFree = null;
      try {
        synchronized (this) {
          loaded = true;
          // Just consumed the last record from the in-memory iterator.
          if (lastPage != null) {
            // Do not free the page here, while we are locking `SpillableIterator`. The `freePage`
            // method locks the `TaskMemoryManager`, and it's a bad idea to lock 2 objects in
            // sequence. We may hit dead lock if another thread locks `TaskMemoryManager` and
            // `SpillableIterator` in sequence, which may happen in
            // `TaskMemoryManager.acquireExecutionMemory`.
            pageToFree = lastPage;
            allocatedPages.clear();
            lastPage = null;
          }
          numRecords--;
          upstream.loadNext();

          // Keep track of the current base object, base offset, record length, and key prefix,
          // so that the current record can still be read in case a spill is triggered and we
          // switch to the spill writer's iterator.
          currentBaseObject = upstream.getBaseObject();
          currentBaseOffset = upstream.getBaseOffset();
          currentRecordLength = upstream.getRecordLength();
          currentKeyPrefix = upstream.getKeyPrefix();
        }
      } finally {
        if (pageToFree != null) {
          freePage(pageToFree);
        }
      }
    }

    @Override
    public Object getBaseObject() {
      return currentBaseObject;
    }

    @Override
    public long getBaseOffset() {
      return currentBaseOffset;
    }

    @Override
    public int getRecordLength() {
      return currentRecordLength;
    }

    @Override
    public long getKeyPrefix() {
      return currentKeyPrefix;
    }
  }

  /**
   * Returns an iterator starts from startIndex, which will return the rows in the order as
   * inserted.
   *
   * It is the caller's responsibility to call `cleanupResources()`
   * after consuming this iterator.
   *
   * TODO: support forced spilling
   */
  //todo 读取数据！！！！！！读取磁盘 + 内存中数据
  public UnsafeSorterIterator getIterator(int startIndex) throws IOException {
    if (spillWriters.isEmpty()) {
      //todo 只从内存中取数据
      assert(inMemSorter != null);
      UnsafeSorterIterator iter = inMemSorter.getSortedIterator();
      moveOver(iter, startIndex);
      return iter;
    } else {
      LinkedList<UnsafeSorterIterator> queue = new LinkedList<>();
      int i = 0;
      //todo 遍历每一个writer
      for (UnsafeSorterSpillWriter spillWriter : spillWriters) {
        //todo 判断读取index
        if (i + spillWriter.recordsSpilled() > startIndex) {
          UnsafeSorterIterator iter = spillWriter.getReader(serializerManager);
          //todo 处理需要跳过的数据
          moveOver(iter, startIndex - i);
          //todo 将迭代器加入队列
          queue.add(iter);
        }
        i += spillWriter.recordsSpilled();
      }
      //todo 内存数据是最后写入的数据！
      if (inMemSorter != null && inMemSorter.numRecords() > 0) {
        UnsafeSorterIterator iter = inMemSorter.getSortedIterator();
        moveOver(iter, startIndex - i);
        queue.add(iter);
      }
      return new ChainedIterator(queue);
    }
  }

  private void moveOver(UnsafeSorterIterator iter, int steps)
      throws IOException {
    if (steps > 0) {
      for (int i = 0; i < steps; i++) {
        if (iter.hasNext()) {
          iter.loadNext();
        } else {
          throw new ArrayIndexOutOfBoundsException("Failed to move the iterator " + steps +
            " steps forward");
        }
      }
    }
  }

  /**
   * Chain multiple UnsafeSorterIterator together as single one.
   */
  static class ChainedIterator extends UnsafeSorterIterator {

    private final Queue<UnsafeSorterIterator> iterators;
    private UnsafeSorterIterator current;
    //todo 数据总条数
    private int numRecords;

    ChainedIterator(Queue<UnsafeSorterIterator> iterators) {
      assert iterators.size() > 0;
      this.numRecords = 0;
      for (UnsafeSorterIterator iter: iterators) {
        this.numRecords += iter.getNumRecords();
      }
      this.iterators = iterators;
      this.current = iterators.remove();
    }

    @Override
    public int getNumRecords() {
      return numRecords;
    }

    @Override
    public long getCurrentPageNumber() {
      return current.getCurrentPageNumber();
    }
    //todo
    @Override
    public boolean hasNext() {
      while (!current.hasNext() && !iterators.isEmpty()) {
        current = iterators.remove();
      }
      //todo 判断每一个迭代器的hashnext
      return current.hasNext();
    }
    //todo
    @Override
    public void loadNext() throws IOException {
      while (!current.hasNext() && !iterators.isEmpty()) {
        current = iterators.remove();
      }
      //todo 每一个迭代器loadnext
      current.loadNext();
    }

    @Override
    public Object getBaseObject() { return current.getBaseObject(); }

    @Override
    public long getBaseOffset() { return current.getBaseOffset(); }

    @Override
    public int getRecordLength() { return current.getRecordLength(); }

    @Override
    public long getKeyPrefix() { return current.getKeyPrefix(); }
  }
}
