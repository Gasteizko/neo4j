/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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

package org.neo4j.kernel.impl.store;

import java.nio.ByteBuffer;

import org.neo4j.kernel.api.constraints.MandatoryNodePropertyConstraint;
import org.neo4j.kernel.api.constraints.NodePropertyConstraint;

public class MandatoryNodePropertyConstraintRule extends NodePropertyConstraintRule
{
    private final int propertyKeyId;

    public static MandatoryNodePropertyConstraintRule mandatoryNodePropertyConstraintRule( long id, int labelId, int propertyKeyId )
    {
        return new MandatoryNodePropertyConstraintRule( id, labelId, propertyKeyId );
    }

    public static MandatoryNodePropertyConstraintRule readMandatoryNodePropertyConstraintRule( long id, int labelId, ByteBuffer buffer )
    {
        return new MandatoryNodePropertyConstraintRule( id, labelId, readPropertyKey( buffer ) );
    }

    private MandatoryNodePropertyConstraintRule( long id, int labelId, int propertyKeyId )
    {
        super( id, labelId, Kind.MANDATORY_NODE_PROPERTY_CONSTRAINT );
        this.propertyKeyId = propertyKeyId;
    }

    @Override
    public int hashCode()
    {
        return super.hashCode() | propertyKeyId;
    }

    @Override
    public boolean equals( Object obj )
    {
        return super.equals( obj ) && propertyKeyId == ((MandatoryNodePropertyConstraintRule) obj).propertyKeyId;
    }

    @Override
    protected String innerToString()
    {
        return ", propertyKey=" + propertyKeyId;
    }

    @Override
    public int length()
    {
        return super.length() +
               4; /* propertyKeyId */
    }

    @Override
    public void serialize( ByteBuffer target )
    {
        super.serialize( target );
        target.putInt( propertyKeyId );
    }

    private static int readPropertyKey( ByteBuffer buffer )
    {
        return buffer.getInt();
    }

    public int getPropertyKey()
    {
        return propertyKeyId;
    }

    @Override
    public NodePropertyConstraint toConstraint()
    {
        return new MandatoryNodePropertyConstraint( getLabel(), getPropertyKey() );
    }

    @Override
    public boolean containsPropertyKeyId( int propertyKeyId )
    {
        return propertyKeyId == this.propertyKeyId;
    }
}
