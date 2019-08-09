/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
#ifndef StringHash_h
#define StringHash_h

#include <wtf/text/AtomicString.h>
#include <wtf/HashTraits.h>
#include <wtf/Hasher.h>

namespace WTF {

    inline bool HashTraits<String>::isEmptyValue(const String& value)
    {
        return value.isNull();
    }

    inline void HashTraits<String>::customDeleteBucket(String& value)
    {
        // See unique_ptr's customDeleteBucket() for an explanation.
        ASSERT(!isDeletedValue(value));
        String valueToBeDestroyed = WTFMove(value);
        constructDeletedValue(value);
    }

    // The hash() functions on StringHash and ASCIICaseInsensitiveHash do not support
    // null strings. get(), contains(), and add() on HashMap<String,..., StringHash>
    // cause a null-pointer dereference when passed null strings.

    // FIXME: We should really figure out a way to put the computeHash function that's
    // currently a member function of StringImpl into this file so we can be a little
    // closer to having all the nearly-identical hash functions in one place.

    struct StringHash {
        static unsigned hash(StringImpl* key) { return key->hash(); }
        static inline bool equal(const StringImpl* a, const StringImpl* b)
        {
            return WTF::equal(*a, *b);
        }

        static unsigned hash(const RefPtr<StringImpl>& key) { return key->hash(); }
        static bool equal(const RefPtr<StringImpl>& a, const RefPtr<StringImpl>& b)
        {
            return equal(a.get(), b.get());
        }
        static bool equal(const RefPtr<StringImpl>& a, const StringImpl* b)
        {
            return equal(a.get(), b);
        }
        static bool equal(const StringImpl* a, const RefPtr<StringImpl>& b)
        {
            return equal(a, b.get());
        }

        static unsigned hash(const String& key) { return key.impl()->hash(); }
        static bool equal(const String& a, const String& b)
        {
            return equal(a.impl(), b.impl());
        }

        static const bool safeToCompareToEmptyOrDeleted = false;
    };

    class ASCIICaseInsensitiveHash {
    public:
        template<typename T> static inline UChar foldCase(T character)
        {
            return toASCIILower(character);
        }

        static unsigned hash(const UChar* data, unsigned length)
        {
            return StringHasher::computeHashAndMaskTop8Bits<UChar, foldCase<UChar>>(data, length);
        }

        static unsigned hash(StringImpl& string)
        {
            if (string.is8Bit())
                return hash(string.characters8(), string.length());
            return hash(string.characters16(), string.length());
        }
        static unsigned hash(StringImpl* string)
        {
            ASSERT(string);
            return hash(*string);
        }

        static unsigned hash(const LChar* data, unsigned length)
        {
            return StringHasher::computeHashAndMaskTop8Bits<LChar, foldCase<LChar>>(data, length);
        }

        static inline unsigned hash(const char* data, unsigned length)
        {
            return hash(reinterpret_cast<const LChar*>(data), length);
        }
        
        static inline bool equal(const StringImpl& a, const StringImpl& b)
        {
            return equalIgnoringASCIICase(a, b);
        }
        static inline bool equal(const StringImpl* a, const StringImpl* b)
        {
            ASSERT(a);
            ASSERT(b);
            return equal(*a, *b);
        }

        static unsigned hash(const RefPtr<StringImpl>& key) 
        {
            return hash(key.get());
        }

        static bool equal(const RefPtr<StringImpl>& a, const RefPtr<StringImpl>& b)
        {
            return equal(a.get(), b.get());
        }

        static unsigned hash(const String& key)
        {
            return hash(key.impl());
        }
        static unsigned hash(const AtomicString& key)
        {
            return hash(key.impl());
        }
        static bool equal(const String& a, const String& b)
        {
            return equal(a.impl(), b.impl());
        }
        static bool equal(const AtomicString& a, const AtomicString& b)
        {
            // FIXME: Is the "a == b" here a helpful optimization?
            // It makes all cases where the strings are not identical slightly slower.
            return a == b || equal(a.impl(), b.impl());
        }

        static const bool safeToCompareToEmptyOrDeleted = false;
    };

    // This hash can be used in cases where the key is a hash of a string, but we don't
    // want to store the string. It's not really specific to string hashing, but all our
    // current uses of it are for strings.
    struct AlreadyHashed : IntHash<unsigned> {
        static unsigned hash(unsigned key) { return key; }

        // To use a hash value as a key for a hash table, we need to eliminate the
        // "deleted" value, which is negative one. That could be done by changing
        // the string hash function to never generate negative one, but this works
        // and is still relatively efficient.
        static unsigned avoidDeletedValue(unsigned hash)
        {
            ASSERT(hash);
            unsigned newHash = hash | (!(hash + 1) << 31);
            ASSERT(newHash);
            ASSERT(newHash != 0xFFFFFFFF);
            return newHash;
        }
    };

}

using WTF::ASCIICaseInsensitiveHash;
using WTF::AlreadyHashed;
using WTF::StringHash;

#endif
