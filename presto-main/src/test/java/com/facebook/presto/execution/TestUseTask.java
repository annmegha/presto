/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution;

import com.facebook.presto.Session;
import com.facebook.presto.common.CatalogSchemaName;
import com.facebook.presto.common.transaction.TransactionId;
import com.facebook.presto.connector.MockConnectorFactory;
import com.facebook.presto.metadata.*;
import com.facebook.presto.spi.*;
import com.facebook.presto.spi.analyzer.ViewDefinition;
import com.facebook.presto.spi.connector.Connector;
import com.facebook.presto.spi.connector.ConnectorCapabilities;
import com.facebook.presto.spi.security.AccessControl;
import com.facebook.presto.spi.security.AccessDeniedException;
import com.facebook.presto.spi.security.AllowAllAccessControl;
import com.facebook.presto.spi.security.DenyAllAccessControl;
import com.facebook.presto.spi.security.Identity;
import com.facebook.presto.sql.analyzer.FeaturesConfig;
import com.facebook.presto.sql.analyzer.SemanticException;
import com.facebook.presto.sql.tree.Identifier;
import com.facebook.presto.sql.tree.Use;
import com.facebook.presto.testing.TestingConnectorContext;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jdk.nashorn.internal.runtime.regexp.joni.encoding.ObjPtr;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import static com.facebook.airlift.concurrent.Threads.daemonThreadsNamed;
import static com.facebook.presto.SessionTestUtils.TEST_SESSION;
import static com.facebook.presto.execution.TaskTestUtils.createQueryStateMachine;
import static com.facebook.presto.metadata.MetadataManager.createTestMetadataManager;
import static com.facebook.presto.spi.ConnectorId.createInformationSchemaConnectorId;
import static com.facebook.presto.spi.ConnectorId.createSystemTablesConnectorId;
import static com.facebook.presto.spi.StandardErrorCode.NOT_FOUND;
import static com.facebook.presto.spi.session.PropertyMetadata.stringProperty;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.CATALOG_NOT_SPECIFIED;
import static com.facebook.presto.testing.TestingSession.createBogusTestingCatalog;
import static com.facebook.presto.testing.TestingSession.testSessionBuilder;
import static com.facebook.presto.tpch.TpchMetadata.TINY_SCHEMA_NAME;
import static com.facebook.presto.transaction.InMemoryTransactionManager.createTestTransactionManager;
import static com.google.common.collect.Sets.immutableEnumSet;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class TestUseTask
{
    private final ExecutorService executor = newCachedThreadPool(daemonThreadsNamed("test-%s"));
    private CatalogManager catalogManager;
    private Catalog testCatalog;
    private TransactionManager transactionManager;
    private MetadataManager metadata=createTestMetadataManager();
    MockConnectorFactory.Builder builder = MockConnectorFactory.builder();
    MockConnectorFactory mockConnectorFactory = builder.withListSchemaNames(connectorSession -> ImmutableList.of("test_schema"))
            .build();
    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        executor.shutdownNow();
    }

    @Test
    public void testUse()
    {
        Use use = new Use(Optional.of(identifier("test_catalog")), identifier("test_schema"));
        String sqlString = "USE test_catalog.test_schema";
        executeUse(use, sqlString, TEST_SESSION);
    }

    @Test
    public void testUseNoCatalog()
    {
        Use use = new Use(Optional.empty(), identifier("test_schema"));
        String sqlString = "USE test_schema";
        Session session = testSessionBuilder()
                .setCatalog(null)
                .setSchema(null)
                .build();
        try {
            executeUse(use, sqlString, session);
        }
        catch (SemanticException e) {
            assertEquals(e.getMessage(),"Catalog must be specified when session catalog is not set");
        }
    }

    @Test
    public void testUseInvalidCatalog()
    {
        Use use = new Use(Optional.of(identifier("invalid_catalog")), identifier("test_schema"));
        String sqlString = "USE invalid_catalog.test_schema";
        try {
            executeUse(use, sqlString, TEST_SESSION);
        }
        catch (PrestoException e) {
            assertEquals(e.getErrorCode(), NOT_FOUND.toErrorCode());
            assertEquals(e.getMessage(), "Catalog does not exist: invalid_catalog");
        }
    }

    @Test
    public void testUseInvalidSchema()
    {
        Use use = new Use(Optional.of(identifier("test_catalog")), identifier("invalid_schema"));
        String sqlString = "USE test_catalog.invalid_schema";
        Session session = testSessionBuilder()
                .setSchema("invalid_schema")
                .build();
        try {
            executeUse(use, sqlString, session);
        }
        catch (PrestoException e) {
            assertEquals(e.getErrorCode(), NOT_FOUND.toErrorCode());
            assertEquals(e.getMessage(), "Schema does not exist: test_catalog.invalid_schema");
        }
    }

    @Test
    public void testUseAccessDenied()
    {
        Use use = new Use(Optional.of(identifier("test_catalog")), identifier("test_schema"));
        String sqlString = "USE test_catalog.test_schema";
        Session session = testSessionBuilder()
                .setIdentity(new Identity("user", Optional.empty()))
                .build();
        AccessControl accessControl = new DenyAllAccessControl();
        try {
            executeUse(use, sqlString, session);
        }
        catch (AccessDeniedException e) {
            assertEquals(e.getMessage(), "Cannot access schema: test_catalog.test_schema");
        }
    }

    private void executeUse(Use use, String sqlString, Session session)
    {
        executeUse(use, sqlString, session, new AllowAllAccessControl());
    }

    private void executeUse(Use use, String sqlString, Session session, AccessControl accessControl)
    {

       // catalogManager = new CatalogManager();
        Connector testConnector = mockConnectorFactory.create("test", ImmutableMap.of(), new TestingConnectorContext());
        catalogManager = new CatalogManager();
        String catalogName = "test_catalog";
        ConnectorId connectorId = new ConnectorId(catalogName);
        catalogManager.registerCatalog(new Catalog(
                catalogName,
                connectorId,
                testConnector,
                createInformationSchemaConnectorId(connectorId),
                testConnector,
                createSystemTablesConnectorId(connectorId),
                testConnector));
        transactionManager = createTestTransactionManager(catalogManager);
        metadata = createTestMetadataManager(transactionManager, new FeaturesConfig());
        QueryStateMachine stateMachine = createQueryStateMachine(sqlString, session, false, transactionManager, executor, metadata);
        UseTask useTask = new UseTask();
        useTask.execute(use, transactionManager, metadata, accessControl, stateMachine, emptyList());
    }
    private Identifier identifier(String name)
    {
        return new Identifier(name);
    }
}
