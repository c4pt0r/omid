/**
 * Copyright 2011-2015 Yahoo Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.omid.tso;

public class LongCache implements Cache {

    private final long [] cache;
    private final int size;
    private final int associativity;
    
    public LongCache(int size, int associativity) {
        this.size = size;
        this.cache = new long[2*(size + associativity)];
        this.associativity = associativity;
    }
    
    /* (non-Javadoc)
     * @see com.yahoo.omid.tso.Cache#set(long, long)
     */
    @Override
    public long set(long key, long value) {
        final int index = index(key);
        int oldestIndex = 0;
        long oldestValue = Long.MAX_VALUE;
        for (int i = 0; i < associativity; ++i) {
            int currIndex = 2 * (index + i);
            if (cache[currIndex] == key) {
                oldestValue = 0;
                oldestIndex = currIndex;
                break;
            }
            if (cache[currIndex + 1] <= oldestValue) {
                oldestValue = cache[currIndex + 1];
                oldestIndex = currIndex;
            }
        }
        cache[oldestIndex] = key;
        cache[oldestIndex + 1] = value;
        return oldestValue;
    }
    
    /* (non-Javadoc)
     * @see com.yahoo.omid.tso.Cache#get(long)
     */
    @Override
    public long get(long key) {
        final int index = index(key);
        for (int i = 0; i < associativity; ++i) {
            int currIndex = 2 * (index + i);
            if (cache[currIndex] == key) {
                return cache[currIndex + 1];
            }
        }
        return 0;
    }

    private int index(long hash) {
        return (int) (Math.abs(hash) % size);
    }

}