/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.index.schema;

import java.util.Arrays;

import org.neo4j.index.internal.gbptree.GBPTree;
import org.neo4j.index.internal.gbptree.Layout;
import org.neo4j.kernel.impl.util.Validator;
import org.neo4j.values.AnyValue;
import org.neo4j.values.SequenceValue;
import org.neo4j.values.storable.TextValue;
import org.neo4j.values.storable.Value;

import static java.lang.String.format;

/**
 * Validates Value[] tuples, whether or not they fit inside a {@link GBPTree} with a layout using {@link CompositeGenericKey}.
 * Most values won't even be serialized to {@link CompositeGenericKey}, values that fit well within the margin.
 */
class GenericIndexKeyValidator implements Validator<Value[]>
{
    private final int maxLength;
    private final Layout<CompositeGenericKey,NativeIndexValue> layout;

    GenericIndexKeyValidator( int maxLength, Layout<CompositeGenericKey,NativeIndexValue> layout )
    {
        this.maxLength = maxLength;
        this.layout = layout;
    }

    @Override
    public void validate( Value[] values )
    {
        int worstCaseSize = worstCaseLength( values );
        if ( worstCaseSize > maxLength )
        {
            int size = actualLength( values );
            if ( size > maxLength )
            {
                throw new IllegalArgumentException( format(
                        "Property value size:%d of %s is too large to index into this particular index. Please see index documentation for limitations.",
                        size, Arrays.toString( values ) ) );
            }
        }
    }

    /**
     * A method for calculating some sort of worst-case length of a value tuple. This have to be a cheap call and can return false positives.
     * It exists to avoid serializing all value tuples into native keys, which can be expensive.
     *
     * @param values the value tuple to calculate some exaggerated worst-case size of.
     * @return the calculated worst-case size of the value tuple.
     */
    private int worstCaseLength( Value[] values )
    {
        int length = Long.BYTES;
        for ( Value value : values )
        {
            // Add some generic overhead, slightly exaggerated
            length += Long.BYTES;
            // Add worst-case length of this value
            length += worstCaseLength( value );
        }
        return length;
    }

    private int worstCaseLength( AnyValue value )
    {
        if ( value.isSequenceValue() )
        {
            SequenceValue sequenceValue = (SequenceValue) value;
            int length = 0;
            for ( int i = 0; i < sequenceValue.length(); i++ )
            {
                length += worstCaseLength( sequenceValue.value( i ) );
            }
            return length;
        }
        else
        {
            switch ( ((Value) value).valueGroup().category() )
            {
            case TEXT:
                // For text, which is very dynamic in its nature do a worst-case off of number of characters in it
                return ((TextValue) value).length() * 4;
            case TEMPORAL:
                // For temporal values we know that they won't be bigger than 4 longs
                return Long.BYTES * 4;
            default:
                // For all else we know that they are roughly a long worth of bytes, so make that 2
                return Long.BYTES * 2;
            }
        }
    }

    private int actualLength( Value[] values )
    {
        CompositeGenericKey key = layout.newKey();
        key.initialize( 0 /*doesn't quite matter for size calculations, but an important method to call*/ );
        for ( int i = 0; i < values.length; i++ )
        {
            key.initFromValue( i, values[i], NativeIndexKey.Inclusion.NEUTRAL );
        }
        return key.size();
    }
}