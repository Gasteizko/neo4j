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

package org.neo4j.kernel.impl.api.integrationtest;

import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.neo4j.function.ThrowingFunction;
import org.neo4j.graphdb.ConstraintViolationException;
import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.StatementTokenNameLookup;
import org.neo4j.kernel.api.TokenNameLookup;
import org.neo4j.kernel.api.exceptions.ConstraintViolationTransactionFailureException;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.api.exceptions.schema.ConstraintVerificationFailedKernelException;
import org.neo4j.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.kernel.impl.api.OperationsFacade;
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge;
import org.neo4j.test.DatabaseRule;
import org.neo4j.test.ImpermanentDatabaseRule;
import org.neo4j.test.ThreadingRule;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.neo4j.test.ThreadingRule.waitingWhileIn;

public abstract class MandatoryPropertyConstraintVerificationIT
{
    private static final String KEY = "Foo";
    private static final String PROPERTY = "bar";

    @Rule
    public final DatabaseRule db = new ImpermanentDatabaseRule();
    @Rule
    public final ThreadingRule thread = new ThreadingRule();

    abstract void createConstraint( DatabaseRule db, String key, String property );

    abstract String constraintCreationMethodName();

    abstract long createOffender( DatabaseRule db, String key );

    abstract String offenderCreationMethodName();

    abstract Class<?> offenderType();

    @Test
    public void shouldFailToCreateConstraintIfSomeNodeLacksTheMandatoryProperty() throws Exception
    {
        // given
        long entityId;
        try ( Transaction tx = db.beginTx() )
        {
            entityId = createOffender( db, KEY );
            tx.success();
        }

        // when
        try
        {
            try ( Transaction tx = db.beginTx() )
            {
                createConstraint( db, KEY, PROPERTY );
                tx.success();
            }
            fail( "expected exception" );
        }
        // then
        catch ( ConstraintViolationException e )
        {
            Throwable cause = e.getCause();
            assertThat( cause, instanceOf( CreateConstraintFailureException.class ) );

            Throwable rootCause = cause.getCause();
            assertThat( rootCause, instanceOf( ConstraintVerificationFailedKernelException.class ) );
            assertThat( userMessageOf( (KernelException) rootCause ),
                    containsString( offenderType().getSimpleName() + "(" + entityId + ")" ) );
        }
    }

    @Test
    public void shouldFailToCreateConstraintIfConcurrentlyCreatedEntityLacksTheMandatoryProperty() throws Exception
    {
        // when
        try
        {
            Future<Void> nodeCreation;
            try ( Transaction tx = db.beginTx() )
            {
                createConstraint( db, KEY, PROPERTY );

                nodeCreation = thread.executeAndAwait( createOffender(), null,
                        waitingWhileIn( OperationsFacade.class, offenderCreationMethodName() ), 5, SECONDS );

                tx.success();
            }
            nodeCreation.get();
            fail( "expected exception" );
        }
        // then, we either fail to create the constraint,
        catch ( ConstraintViolationException e )
        {
            assertThat( e.getCause(), instanceOf( CreateConstraintFailureException.class ) );
        }
        // or we fail to create the offending node
        catch ( ExecutionException e )
        {
            assertThat( e.getCause(), instanceOf( ConstraintViolationException.class ) );
            assertThat( e.getCause().getCause(), instanceOf( ConstraintViolationTransactionFailureException.class ) );
        }
    }

    @Test
    public void shouldFailToCreateConstraintIfConcurrentlyCommittedEntityLacksTheMandatoryProperty() throws Exception
    {
        // when
        try
        {
            Future<Void> constraintCreation;
            try ( Transaction tx = db.beginTx() )
            {
                createOffender( db, KEY );

                constraintCreation = thread.executeAndAwait( createConstraint(), null,
                        waitingWhileIn( OperationsFacade.class, constraintCreationMethodName() ), 5, SECONDS );

                tx.success();
            }
            constraintCreation.get();
            fail( "expected exception" );
        }
        // then, we either fail to create the constraint,
        catch ( ExecutionException e )
        {
            assertThat( e.getCause(), instanceOf( ConstraintViolationException.class ) );
            assertThat( e.getCause().getCause(), instanceOf( CreateConstraintFailureException.class ) );
        }
        // or we fail to create the offending node
        catch ( ConstraintViolationException e )
        {
            assertThat( e.getCause(), instanceOf( ConstraintViolationTransactionFailureException.class ) );
        }
    }

    private String userMessageOf( KernelException exception )
    {
        try ( Transaction ignored = db.beginTx() )
        {
            DependencyResolver dependencyResolver = db.getGraphDatabaseAPI().getDependencyResolver();
            Statement statement = dependencyResolver.resolveDependency( ThreadToStatementContextBridge.class ).get();
            TokenNameLookup tokenNameLookup = new StatementTokenNameLookup( statement.readOperations() );
            return exception.getUserMessage( tokenNameLookup );
        }
    }

    private ThrowingFunction<Void,Void,RuntimeException> createOffender()
    {
        return new ThrowingFunction<Void,Void,RuntimeException>()
        {
            @Override
            public Void apply( Void aVoid ) throws RuntimeException
            {
                try ( Transaction tx = db.beginTx() )
                {
                    createOffender( db, KEY );
                    tx.success();
                }
                return null;
            }
        };
    }

    private ThrowingFunction<Void,Void,RuntimeException> createConstraint()
    {
        return new ThrowingFunction<Void,Void,RuntimeException>()
        {
            @Override
            public Void apply( Void aVoid ) throws RuntimeException
            {
                try ( Transaction tx = db.beginTx() )
                {
                    createConstraint( db, KEY, PROPERTY );
                    tx.success();
                }
                return null;
            }
        };
    }
}
