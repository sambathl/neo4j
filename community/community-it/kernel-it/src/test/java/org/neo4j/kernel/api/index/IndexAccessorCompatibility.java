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
package org.neo4j.kernel.api.index;

import org.junit.After;
import org.junit.Before;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.neo4j.collection.PrimitiveLongResourceIterator;
import org.neo4j.internal.kernel.api.IndexQuery;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.api.index.IndexUpdateMode;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingConfig;
import org.neo4j.storageengine.api.schema.IndexDescriptor;
import org.neo4j.storageengine.api.schema.IndexReader;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.ValueGroup;

public abstract class IndexAccessorCompatibility extends IndexProviderCompatibilityTestSuite.Compatibility
{
    protected IndexAccessor accessor;
    // This map is for spatial values, so that the #query method can lookup the values for the results and filter properly
    private Map<Long,Value[]> committedValues = new HashMap<>();

    public IndexAccessorCompatibility( IndexProviderCompatibilityTestSuite testSuite, IndexDescriptor descriptor )
    {
        super( testSuite, descriptor );
    }

    @Before
    public void before() throws Exception
    {
        IndexSamplingConfig indexSamplingConfig = new IndexSamplingConfig( Config.defaults() );
        IndexPopulator populator = indexProvider.getPopulator( descriptor, indexSamplingConfig );
        populator.create();
        populator.close( true );
        accessor = indexProvider.getOnlineAccessor( descriptor, indexSamplingConfig );
    }

    @After
    public void after()
    {
        try
        {
            testSuite.consistencyCheck( accessor );
        }
        finally
        {
            accessor.drop();
            accessor.close();
        }
    }

    protected List<Long> query( IndexQuery... predicates ) throws Exception
    {
        try ( IndexReader reader = accessor.newReader();
              PrimitiveLongResourceIterator unfilteredResults = reader.query( predicates ) )
        {
            List<Long> list = new LinkedList<>();
            while ( unfilteredResults.hasNext() )
            {
                long entityId = unfilteredResults.next();
                if ( passesFilter( entityId, predicates ) )
                {
                    list.add( entityId );
                }
            }
            Collections.sort( list );
            return list;
        }
    }

    /**
     * Run the Value[] from a particular entityId through the list of IndexQuery[] predicates to see if they all accept the value.
     */
    private boolean passesFilter( long entityId, IndexQuery[] predicates )
    {
        if ( predicates.length == 1 && predicates[0] instanceof IndexQuery.ExistsPredicate )
        {
            return true;
        }

        Value[] values = committedValues.get( entityId );
        for ( int i = 0; i < values.length; i++ )
        {
            IndexQuery predicate = predicates[i];
            if ( predicate.valueGroup() == ValueGroup.GEOMETRY || predicate.valueGroup() == ValueGroup.GEOMETRY_ARRAY ||
                    (predicate.valueGroup() == ValueGroup.NUMBER && !testSuite.supportFullValuePrecisionForNumbers()) )
            {
                if ( !predicates[i].acceptsValue( values[i] ) )
                {
                    return false;
                }
            }
            // else there's no functional need to let values, other than those of GEOMETRY type, to pass through the IndexQuery filtering
            // avoiding this filtering will have testing be more strict in what index readers returns.
        }
        return true;
    }

    /**
     * Commit these updates to the index. Also store the values, which currently are stored for all types except geometry,
     * so therefore it's done explicitly here so that we can filter on them later.
     */
    void updateAndCommit( Collection<IndexEntryUpdate<?>> updates ) throws IndexEntryConflictException
    {
        try ( IndexUpdater updater = accessor.newUpdater( IndexUpdateMode.ONLINE ) )
        {
            for ( IndexEntryUpdate<?> update : updates )
            {
                updater.process( update );
                switch ( update.updateMode() )
                {
                case ADDED:
                case CHANGED:
                    committedValues.put( update.getEntityId(), update.values() );
                    break;
                case REMOVED:
                    committedValues.remove( update.getEntityId() );
                    break;
                default:
                    throw new IllegalArgumentException( "Unknown update mode of " + update );
                }
            }
        }
    }
}
