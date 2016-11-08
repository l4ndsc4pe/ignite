/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.database.tree.io;

import java.nio.ByteBuffer;
import org.apache.ignite.internal.pagemem.PageIdUtils;
import org.apache.ignite.internal.processors.cache.database.tree.util.PageHandler;

/**
 * We use dedicated page for tracking pages updates.
 * Also we divide such 'tracking' pages on two half, first is used for check that page was changed or not
 * (during incremental backup), second - to accumulate changed for next backup.
 *
 * You cannot test change for not started backup! because it will cause of preparation for backup.
 *
 * Implementation. For each page there is own bit in both half. Tracking page is used for tracking N page after it.
 * N depends on page size (how many bytes we can use for tracking).
 *
 *
 *                      +-----------------------------------------+-----------------------------------------+
 *                      |                left half                |               right half                |
 * +---------+----------+----+------------------------------------+----+------------------------------------+
 * |  HEADER | Last     |size|                                    |size|                                    |
 * |         | BackupId |2b. |  tracking bits                     |2b. |  tracking bits                     |
 * +---------+----------+----+------------------------------------+----+------------------------------------+
 *
 */
public class TrackingPageIO extends PageIO {
    /** */
    public static final IOVersions<TrackingPageIO> VERSIONS = new IOVersions<>(
        new TrackingPageIO(1)
    );

    /** Last backup offset. */
    public static final int LAST_BACKUP_TAG_OFFSET = COMMON_HEADER_END;

    /** Size field offset. */
    public static final int SIZE_FIELD_OFFSET = LAST_BACKUP_TAG_OFFSET + 8;

    /** 'Size' field size. */
    public static final int SIZE_FIELD_SIZE = 2;

    /** Bitmap offset. */
    public static final int BITMAP_OFFSET = SIZE_FIELD_OFFSET + SIZE_FIELD_SIZE;

    /** Count of extra page. */
    public static final int COUNT_OF_EXTRA_PAGE = 1;

    /**
     * @param ver Page format version.
     */
    protected TrackingPageIO(int ver) {
        super(PageIO.T_PAGE_UPDATE_TRACKING, ver);
    }

    /**
     * Will mark pageId as changed for next (!) backupId
     *
     * @param buf Buffer.
     * @param pageId Page id.
     * @param nextBackupTag tag of next backup.
     * @param pageSize Page size.
     */
    public boolean markChanged(ByteBuffer buf, long pageId, long nextBackupTag, long lastSuccessfulBackupTag, int pageSize) {
        validateBackupId(buf, nextBackupTag, lastSuccessfulBackupTag, pageSize);

        int cntOfPage = countOfPageToTrack(pageSize);

        int idxToUpdate = (PageIdUtils.pageIndex(pageId) - COUNT_OF_EXTRA_PAGE) % cntOfPage;

        int sizeOff = useLeftHalf(nextBackupTag) ? SIZE_FIELD_OFFSET : BITMAP_OFFSET + (cntOfPage >> 3);

        short newSize = (short)(countOfChangedPage(buf, nextBackupTag, pageSize) + 1);

        buf.putShort(sizeOff, newSize);

        assert newSize == countOfChangedPage(buf, nextBackupTag, pageSize);

        int idx = sizeOff + SIZE_FIELD_SIZE + (idxToUpdate >> 3);

        byte byteToUpdate = buf.get(idx);

        int updateTemplate = 1 << (idxToUpdate & 0b111);

        byte newVal =  (byte) (byteToUpdate | updateTemplate);

        buf.put(idx, newVal);

        return newVal != byteToUpdate;
    }

    /**
     * @param buf Buffer.
     * @param nextBackupTag Next backup id.
     * @param lastSuccessfulBackupId Last successful backup id.
     * @param pageSize Page size.
     */
    private void validateBackupId(ByteBuffer buf, long nextBackupTag, long lastSuccessfulBackupId, int pageSize) {
        assert nextBackupTag != lastSuccessfulBackupId : "nextBackupTag = " + nextBackupTag +
            ", lastSuccessfulBackupId = " + lastSuccessfulBackupId;

        long last = getLastBackupTag(buf);

        assert last <= nextBackupTag : "last = " + last + ", nextBackupTag = " + nextBackupTag;

        if (nextBackupTag == last) //everything is ok
            return;

        int cntOfPage = countOfPageToTrack(pageSize);

        if (last <= lastSuccessfulBackupId) { //we can drop our data
            buf.putLong(LAST_BACKUP_TAG_OFFSET, nextBackupTag);

            PageHandler.zeroMemory(buf, SIZE_FIELD_OFFSET, buf.capacity() - SIZE_FIELD_OFFSET);
        } else { //we can't drop data, it is still necessary for incremental backups
            int len = cntOfPage >> 3;

            int sizeOff = useLeftHalf(nextBackupTag) ? SIZE_FIELD_OFFSET : BITMAP_OFFSET + len;
            int sizeOff2 = !useLeftHalf(nextBackupTag) ? SIZE_FIELD_OFFSET : BITMAP_OFFSET + len;

            if (last - lastSuccessfulBackupId == 1) { //we should keep only data in last half
                //new data will be written in the same half, we should move old data to another half
                if ((nextBackupTag - last) % 2 == 0)
                    PageHandler.copyMemory(buf, buf, sizeOff, sizeOff2, len + SIZE_FIELD_SIZE);
            } else { //last - lastSuccessfulBackupId > 1, e.g. we should merge two half in one
                int newSize = 0;
                int i = 0;

                for (; i < len - 8; i += 8) {
                    long newVal = buf.getLong(sizeOff + SIZE_FIELD_SIZE + i) | buf.getLong(sizeOff2 + SIZE_FIELD_SIZE + i);

                    newSize += Long.bitCount(newVal);

                    buf.putLong(sizeOff2 + SIZE_FIELD_SIZE + i, newVal);
                }

                for (i -= 8; i < len; i ++) {
                    byte newVal = (byte) (buf.get(sizeOff + SIZE_FIELD_SIZE + i) | buf.get(sizeOff2 + SIZE_FIELD_SIZE + i));

                    newSize += Integer.bitCount(newVal & 0xFF);

                    buf.put(sizeOff2 + SIZE_FIELD_SIZE + i, newVal);
                }

                buf.putShort(sizeOff2, (short)newSize);
            }

            buf.putLong(LAST_BACKUP_TAG_OFFSET, nextBackupTag);

            PageHandler.zeroMemory(buf, sizeOff, len + SIZE_FIELD_SIZE);
        }
    }

    /**
     * @param buf Buffer.
     */
    long getLastBackupTag(ByteBuffer buf) {
        return buf.getLong(LAST_BACKUP_TAG_OFFSET);
    }

    /**
     * Check that pageId was marked as changed between previous backup finish and current backup start.
     *
     * @param buf Buffer.
     * @param pageId Page id.
     * @param curBackupTag Backup tag.
     * @param pageSize Page size.
     */
    public boolean wasChanged(ByteBuffer buf, long pageId, long curBackupTag, long lastSuccessfulBackupTag, int pageSize) {
        validateBackupId(buf, curBackupTag + 1, lastSuccessfulBackupTag, pageSize);

        if (countOfChangedPage(buf, curBackupTag, pageSize) < 1)
            return false;

        int cntOfPage = countOfPageToTrack(pageSize);

        int idxToTest = (PageIdUtils.pageIndex(pageId) - COUNT_OF_EXTRA_PAGE) % cntOfPage;

        byte byteToTest;

        if (useLeftHalf(curBackupTag))
            byteToTest = buf.get(BITMAP_OFFSET + (idxToTest >> 3));
        else
            byteToTest = buf.get(BITMAP_OFFSET + SIZE_FIELD_SIZE + ((idxToTest + cntOfPage) >> 3));

        int testTemplate = 1 << (idxToTest & 0b111);

        return ((byteToTest & testTemplate) ^ testTemplate) == 0;
    }

    /**
     * @param buf Buffer.
     * @param backupTag Backup tag.
     * @param pageSize Page size.
     *
     * @return count of pages which were marked as change for given backupTag
     */
    public short countOfChangedPage(ByteBuffer buf, long backupTag, int pageSize) {
        long dif = getLastBackupTag(buf) - backupTag;

        if (dif != 0 && dif != 1)
            return -1;

        if (useLeftHalf(backupTag))
            return buf.getShort(SIZE_FIELD_OFFSET);
        else
            return buf.getShort(BITMAP_OFFSET + (countOfPageToTrack(pageSize) >> 3));
    }

    /**
     * @param backupTag Backup id.
     *
     * @return true if backupTag is odd, otherwise - false
     */
    boolean useLeftHalf(long backupTag) {
        return (backupTag & 0b1) == 0;
    }

    /**
     * @param pageId Page id.
     * @param pageSize Page size.
     * @return pageId of tracking page which set pageId belongs to
     */
    public long trackingPageFor(long pageId, int pageSize) {
        assert PageIdUtils.pageIndex(pageId) > 0;

        int pageIdx = ((PageIdUtils.pageIndex(pageId) - COUNT_OF_EXTRA_PAGE) /
            countOfPageToTrack(pageSize)) * countOfPageToTrack(pageSize) + COUNT_OF_EXTRA_PAGE;

        long trackingPageId = PageIdUtils.pageId(PageIdUtils.partId(pageId), PageIdUtils.flag(pageId), pageIdx);

        assert PageIdUtils.pageIndex(trackingPageId) <= PageIdUtils.pageIndex(pageId);

        return trackingPageId;
    }

    /**
     * @param pageSize Page size.
     *
     * @return how many page we can track with 1 page
     */
    public int countOfPageToTrack(int pageSize) {
        return ((pageSize - SIZE_FIELD_OFFSET) / 2 - SIZE_FIELD_SIZE)  << 3;
    }

    /**
     * @param buf Buffer.
     * @param start Start.
     * @param curBackupTag Backup id.
     * @param pageSize Page size.
     * @return set pageId if it was changed or next closest one, if there is no changed page null will be returned
     */
    public Long findNextChangedPage(ByteBuffer buf, long start, long curBackupTag, long lastSuccessfulBackupTag, int pageSize) {
        validateBackupId(buf, curBackupTag + 1, lastSuccessfulBackupTag, pageSize);

        int cntOfPage = countOfPageToTrack(pageSize);

        long trackingPage = trackingPageFor(start, pageSize);

        if (start == trackingPage)
            return trackingPage;

        if (countOfChangedPage(buf, curBackupTag, pageSize) <= 0)
            return null;

        int idxToStartTest = (PageIdUtils.pageIndex(start) - COUNT_OF_EXTRA_PAGE) % cntOfPage;

        int zeroIdx = useLeftHalf(curBackupTag)? BITMAP_OFFSET : BITMAP_OFFSET + SIZE_FIELD_SIZE + (cntOfPage >> 3);

        int startIdx = zeroIdx + (idxToStartTest >> 3);

        int idx = startIdx;

        int stopIdx = zeroIdx + (cntOfPage >> 3);

        while (idx < stopIdx) {
            byte byteToTest = buf.get(idx);

            if (byteToTest != 0) {
                int foundSetBit;
                if ((foundSetBit = foundSetBit(byteToTest, idx == startIdx ? (idxToStartTest & 0b111) : 0)) != -1) {
                    long foundPageId = PageIdUtils.pageId(
                        PageIdUtils.partId(start),
                        PageIdUtils.flag(start),
                        PageIdUtils.pageIndex(trackingPage) + ((idx - zeroIdx) << 3) + foundSetBit);

                    assert wasChanged(buf, foundPageId, curBackupTag, lastSuccessfulBackupTag, pageSize);
                    assert trackingPageFor(foundPageId, pageSize) == trackingPage;

                    return foundPageId;
                }
            }

            idx++;
        }

        return null;
    }

    /**
     * @param byteToTest Byte to test.
     * @param firstBitToTest First bit to test.
     */
    private static int foundSetBit(byte byteToTest, int firstBitToTest) {
        assert firstBitToTest < 8;

        for (int i = firstBitToTest; i < 8; i++) {
            int testTemplate = 1 << i;

            if (((byteToTest & testTemplate) ^ testTemplate) == 0)
                return i;
        }

        return -1;
    }
}
