/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */

package com.orientechnologies.orient.core.storage.impl.local.paginated;

import com.orientechnologies.orient.core.exception.OStorageException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.index.hashindex.local.cache.OCacheEntry;
import com.orientechnologies.orient.core.index.hashindex.local.cache.ODiskCache;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperation;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurableComponent;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurablePage;

import java.io.IOException;
import java.util.Arrays;

/**
 * @author Andrey Lomakin (a.lomakin-at-orientechnologies.com)
 * @since 10/7/13
 */
public class OClusterPositionMap extends ODurableComponent {
  public static final String DEF_EXTENSION = ".cpm";

  private final ODiskCache   diskCache;
  private String             name;
  private long               fileId;
  private boolean            useWal;

  public OClusterPositionMap(OAbstractPaginatedStorage storage, ODiskCache diskCache, String name, boolean useWal) {
    acquireExclusiveLock();
    try {
      this.diskCache = diskCache;
      this.name = name;
      this.useWal = useWal;

      init(storage);
    } finally {
      releaseExclusiveLock();
    }
  }

  public void setUseWal(boolean useWal) {
    acquireExclusiveLock();
    try {
      this.useWal = useWal;
    } finally {
      releaseExclusiveLock();
    }
  }

  public void open() throws IOException {
    acquireExclusiveLock();
    try {
      fileId = diskCache.openFile(name + DEF_EXTENSION);
    } finally {
      releaseExclusiveLock();
    }
  }

  public void create() throws IOException {
    acquireExclusiveLock();
    try {
      fileId = diskCache.addFile(name + DEF_EXTENSION);
    } finally {
      releaseExclusiveLock();
    }
  }

  public void flush() throws IOException {
    acquireSharedLock();
    try {
      diskCache.flushFile(fileId);
    } finally {
      releaseSharedLock();
    }
  }

  public void close(boolean flush) throws IOException {
    acquireExclusiveLock();
    try {
      diskCache.closeFile(fileId, flush);
    } finally {
      releaseExclusiveLock();
    }
  }

  public void truncate() throws IOException {
    acquireExclusiveLock();
    try {
      diskCache.truncateFile(fileId);
    } finally {
      releaseExclusiveLock();
    }
  }

  public void delete() throws IOException {
    acquireExclusiveLock();
    try {
      diskCache.deleteFile(fileId);
    } finally {
      releaseExclusiveLock();
    }
  }

  public void rename(String newName) throws IOException {
    acquireExclusiveLock();
    try {
      diskCache.renameFile(fileId, this.name + DEF_EXTENSION, newName + DEF_EXTENSION);
      name = newName;
    } finally {
      releaseExclusiveLock();
    }
  }

  public long add(long pageIndex, int recordPosition) throws IOException {
    acquireExclusiveLock();
    try {
      OAtomicOperation atomicOperation = storage.getAtomicOperationsManager().getCurrentOperation();

      long lastPage = getFilledUpTo(atomicOperation, diskCache, fileId) - 1;
      OCacheEntry cacheEntry = loadPage(atomicOperation, fileId, lastPage, false, diskCache);
      if (cacheEntry == null)
        cacheEntry = addPage(atomicOperation, fileId, diskCache);

      cacheEntry.acquireExclusiveLock();
      try {
        atomicOperation = startAtomicOperation();

        OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, getChangesTree(atomicOperation, cacheEntry));
        if (bucket.isFull()) {
          cacheEntry.releaseExclusiveLock();
          diskCache.release(cacheEntry);

          cacheEntry = addPage(atomicOperation, fileId, diskCache);

          cacheEntry.acquireExclusiveLock();
          bucket = new OClusterPositionMapBucket(cacheEntry, getChangesTree(atomicOperation, cacheEntry));
        }

        final long index = bucket.add(pageIndex, recordPosition);
        final long result = index + cacheEntry.getPageIndex() * OClusterPositionMapBucket.MAX_ENTRIES;

        endAtomicOperation(false);
        return result;
      } catch (Throwable e) {
        endAtomicOperation(true);
        throw new OStorageException("Error during creation of mapping between logical adn physical record position.", e);
      } finally {
        cacheEntry.releaseExclusiveLock();
        diskCache.release(cacheEntry);
      }
    } finally {
      releaseExclusiveLock();
    }
  }

  public OClusterPositionMapBucket.PositionEntry get(final long clusterPosition) throws IOException {
    acquireSharedLock();
    try {
      long pageIndex = clusterPosition / OClusterPositionMapBucket.MAX_ENTRIES;
      int index = (int) (clusterPosition % OClusterPositionMapBucket.MAX_ENTRIES);

      final OAtomicOperation atomicOperation = storage.getAtomicOperationsManager().getCurrentOperation();

      if (pageIndex >= getFilledUpTo(atomicOperation, diskCache, fileId))
        return null;

      final OCacheEntry cacheEntry = loadPage(atomicOperation, fileId, pageIndex, false, diskCache);
      try {
        final OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, getChangesTree(atomicOperation,
            cacheEntry));
        return bucket.get(index);
      } finally {
        diskCache.release(cacheEntry);
      }
    } finally {
      releaseSharedLock();
    }
  }

  public OClusterPositionMapBucket.PositionEntry remove(final long clusterPosition) throws IOException {
    acquireExclusiveLock();
    try {
      long pageIndex = clusterPosition / OClusterPositionMapBucket.MAX_ENTRIES;
      int index = (int) (clusterPosition % OClusterPositionMapBucket.MAX_ENTRIES);
      OAtomicOperation atomicOperation = storage.getAtomicOperationsManager().getCurrentOperation();

      final OCacheEntry cacheEntry = loadPage(atomicOperation, fileId, pageIndex, false, diskCache);
      cacheEntry.acquireExclusiveLock();
      try {
        atomicOperation = startAtomicOperation();
        final OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, getChangesTree(atomicOperation,
            cacheEntry));

        OClusterPositionMapBucket.PositionEntry positionEntry = bucket.remove(index);
        if (positionEntry == null)
          return null;

        endAtomicOperation(false);
        return positionEntry;
      } catch (Throwable e) {
        endAtomicOperation(true);

        throw new OStorageException("Error during removal of mapping between logical and physical record position.", e);
      } finally {
        cacheEntry.releaseExclusiveLock();
        diskCache.release(cacheEntry);
      }
    } finally {
      releaseExclusiveLock();
    }
  }

  public long[] higherPositions(final long clusterPosition) throws IOException {
    acquireSharedLock();
    try {
      if (clusterPosition == Long.MAX_VALUE)
        return new long[0];

      return ceilingPositions(clusterPosition + 1);
    } finally {
      releaseSharedLock();
    }
  }

  public long[] ceilingPositions(long clusterPosition) throws IOException {
    acquireSharedLock();
    try {
      if (clusterPosition < 0)
        clusterPosition = 0;

      long pageIndex = clusterPosition / OClusterPositionMapBucket.MAX_ENTRIES;
      int index = (int) (clusterPosition % OClusterPositionMapBucket.MAX_ENTRIES);

      OAtomicOperation atomicOperation = storage.getAtomicOperationsManager().getCurrentOperation();
      final long filledUpTo = getFilledUpTo(atomicOperation, diskCache, fileId);

      if (pageIndex >= filledUpTo)
        return new long[0];

      long[] result = null;
      do {
        OCacheEntry cacheEntry = loadPage(atomicOperation, fileId, pageIndex, false, diskCache);
        OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, getChangesTree(atomicOperation, cacheEntry));
        int resultSize = bucket.getSize() - index;

        if (resultSize <= 0) {
          diskCache.release(cacheEntry);
          pageIndex++;
          index = 0;
        } else {
          int entriesCount = 0;
          long startIndex = cacheEntry.getPageIndex() * OClusterPositionMapBucket.MAX_ENTRIES + index;

          result = new long[resultSize];
          for (int i = 0; i < resultSize; i++) {
            if (bucket.exists(i + index)) {
              result[entriesCount] = startIndex + i;
              entriesCount++;
            }
          }

          if (entriesCount == 0) {
            result = null;
            pageIndex++;
            index = 0;
          } else
            result = Arrays.copyOf(result, entriesCount);

          diskCache.release(cacheEntry);
        }
      } while (result == null && pageIndex < filledUpTo);

      if (result == null)
        result = new long[0];

      return result;
    } finally {
      releaseSharedLock();
    }
  }

  public long[] lowerPositions(final long clusterPosition) throws IOException {
    acquireSharedLock();
    try {
      if (clusterPosition == 0)
        return new long[0];

      return floorPositions(clusterPosition - 1);
    } finally {
      releaseSharedLock();
    }
  }

  public long[] floorPositions(final long clusterPosition) throws IOException {
    acquireSharedLock();
    try {
      if (clusterPosition < 0)
        return new long[0];

      long pageIndex = clusterPosition / OClusterPositionMapBucket.MAX_ENTRIES;
      int index = (int) (clusterPosition % OClusterPositionMapBucket.MAX_ENTRIES);

      OAtomicOperation atomicOperation = storage.getAtomicOperationsManager().getCurrentOperation();

      final long filledUpTo = getFilledUpTo(atomicOperation, diskCache, fileId);
      long[] result;

      if (pageIndex >= filledUpTo) {
        pageIndex = filledUpTo - 1;
        index = Integer.MIN_VALUE;
      }

      do {
        OCacheEntry cacheEntry = loadPage(atomicOperation, fileId, pageIndex, false, diskCache);
        OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, getChangesTree(atomicOperation, cacheEntry));
        if (index == Integer.MIN_VALUE)
          index = bucket.getSize() - 1;

        int resultSize = index + 1;
        int entriesCount = 0;

        long startPosition = cacheEntry.getPageIndex() * OClusterPositionMapBucket.MAX_ENTRIES;
        result = new long[resultSize];

        for (int i = 0; i < resultSize; i++) {
          if (bucket.exists(i)) {
            result[entriesCount] = startPosition + i;
            entriesCount++;
          }
        }

        if (entriesCount == 0) {
          result = null;
          pageIndex--;
          index = Integer.MIN_VALUE;
        } else
          result = Arrays.copyOf(result, entriesCount);

        diskCache.release(cacheEntry);
      } while (result == null && pageIndex >= 0);

      if (result == null)
        result = new long[0];

      return result;
    } finally {
      releaseSharedLock();
    }
  }

  public long getFirstPosition() throws IOException {
    acquireSharedLock();
    try {
      OAtomicOperation atomicOperation = storage.getAtomicOperationsManager().getCurrentOperation();
      final long filledUpTo = getFilledUpTo(atomicOperation, diskCache, fileId);
      for (long pageIndex = 0; pageIndex < filledUpTo; pageIndex++) {
        OCacheEntry cacheEntry = loadPage(atomicOperation, fileId, pageIndex, false, diskCache);
        try {
          OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, getChangesTree(atomicOperation, cacheEntry));
          int bucketSize = bucket.getSize();

          for (int index = 0; index < bucketSize; index++) {
            if (bucket.exists(index))
              return pageIndex * OClusterPositionMapBucket.MAX_ENTRIES + index;
          }
        } finally {
          diskCache.release(cacheEntry);
        }
      }

      return ORID.CLUSTER_POS_INVALID;
    } finally {
      releaseSharedLock();
    }
  }

  public long getLastPosition() throws IOException {
    acquireSharedLock();
    try {
      OAtomicOperation atomicOperation = storage.getAtomicOperationsManager().getCurrentOperation();
      final long filledUpTo = getFilledUpTo(atomicOperation, diskCache, fileId);

      for (long pageIndex = filledUpTo - 1; pageIndex >= 0; pageIndex--) {
        OCacheEntry cacheEntry = loadPage(atomicOperation, fileId, pageIndex, false, diskCache);
        try {
          OClusterPositionMapBucket bucket = new OClusterPositionMapBucket(cacheEntry, getChangesTree(atomicOperation, cacheEntry));
          final int bucketSize = bucket.getSize();

          for (int index = bucketSize - 1; index >= 0; index--) {
            if (bucket.exists(index))
              return pageIndex * OClusterPositionMapBucket.MAX_ENTRIES + index;
          }
        } finally {
          diskCache.release(cacheEntry);
        }
      }

      return ORID.CLUSTER_POS_INVALID;
    } finally {
      releaseSharedLock();
    }
  }

  public boolean wasSoftlyClosed() throws IOException {
    acquireSharedLock();
    try {
      return diskCache.wasSoftlyClosed(fileId);
    } finally {
      releaseSharedLock();
    }
  }

  @Override
  protected void endAtomicOperation(final boolean rollback) throws IOException {
    if (useWal)
      super.endAtomicOperation(rollback);
  }

  @Override
  protected OAtomicOperation startAtomicOperation() throws IOException {
    if (useWal)
      return super.startAtomicOperation();

    return null;
  }
}
