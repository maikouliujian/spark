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

package org.apache.spark.unsafe.memory;

import javax.annotation.Nullable;

/**
 * A memory location. Tracked either by a memory address (with off-heap allocation),
 * or by an offset from a JVM object (on-heap allocation).
 */
//todo 为了统一管理 Off Heap 和 On Heap 内存空间，Tungsten 定义了统一的 128 位内存地址，简称 Tungsten 地址。
// Tungsten 地址分为两部分：前 64 位为recordPointer【13 (pagenumber) + 51(offset)】，后 64 位是Base Offset。
//todo 寻址时候，无论是堆内还是堆外，都要通过recordPointer的前13位在pagetable中找到对应的MemoryBlock
  //todo 堆外：MemoryBlock中的obj为null，对象地址为：Base Offset + offset
  //todo 堆内：对象在MemoryBlock中的obj的offset处；

public class MemoryLocation {

  @Nullable
  Object obj; //todo【堆内有用，堆外无用】堆内对象【一个obj可以存储多个数据条目，使用内存页大幅缩减了 存储所需的对象数量】; 如果是Off Heap，则为null;

  long offset; //todo【堆内无用，堆外有用】64位，堆内对象无用; 堆外对象的baseoffset

  public MemoryLocation(@Nullable Object obj, long offset) {
    this.obj = obj;
    this.offset = offset;
  }

  public MemoryLocation() {
    this(null, 0);
  }

  public void setObjAndOffset(Object newObj, long newOffset) {
    this.obj = newObj;
    this.offset = newOffset;
  }

  public final Object getBaseObject() {
    return obj;
  }

  public final long getBaseOffset() {
    return offset;
  }
}
