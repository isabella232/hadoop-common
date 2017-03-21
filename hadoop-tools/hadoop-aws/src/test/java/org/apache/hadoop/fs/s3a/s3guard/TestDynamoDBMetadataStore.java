/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.s3a.s3guard;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughputDescription;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.services.dynamodbv2.model.TableDescription;

import org.apache.commons.collections.CollectionUtils;
import org.apache.hadoop.fs.s3a.Tristate;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.s3a.Constants;
import org.apache.hadoop.fs.s3a.MockS3ClientFactory;
import org.apache.hadoop.fs.s3a.S3AFileStatus;
import org.apache.hadoop.fs.s3a.S3AFileSystem;
import org.apache.hadoop.fs.s3a.S3ClientFactory;
import org.apache.hadoop.security.UserGroupInformation;

import static org.apache.hadoop.fs.s3a.Constants.*;
import static org.apache.hadoop.fs.s3a.s3guard.PathMetadataDynamoDBTranslation.*;
import static org.apache.hadoop.fs.s3a.s3guard.DynamoDBMetadataStore.*;

/**
 * Test that {@link DynamoDBMetadataStore} implements {@link MetadataStore}.
 *
 * In this unit test, we use an in-memory DynamoDBLocal server instead of real
 * AWS DynamoDB. An {@link S3AFileSystem} object is created and shared for
 * initializing {@link DynamoDBMetadataStore} objects.  There are no real S3
 * request issued as the underlying AWS S3Client is mocked.  You won't be
 * charged bills for AWS S3 or DynamoDB when you run this test.
 *
 * According to the base class, every test case will have independent contract
 * to create a new {@link DynamoDBMetadataStore} instance and initializes it.
 * A table will be created for each test by the test contract, and will be
 * destroyed after the test case finishes.
 */
public class TestDynamoDBMetadataStore extends MetadataStoreTestBase {
  private static final Logger LOG =
      LoggerFactory.getLogger(TestDynamoDBMetadataStore.class);
  private static final String BUCKET = "TestDynamoDBMetadataStore";
  private static final String S3URI =
      URI.create(Constants.FS_S3A + "://" + BUCKET + "/").toString();
  public static final PrimaryKey
      VERSION_MARKER_PRIMARY_KEY = createVersionMarkerPrimaryKey(
      DynamoDBMetadataStore.VERSION_MARKER);

  /** The DynamoDB instance that can issue requests directly to server. */
  private static DynamoDB dynamoDB;

  @Rule
  public final Timeout timeout = new Timeout(60 * 1000);

  /**
   * Start the in-memory DynamoDBLocal server and initializes s3 file system.
   */
  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    DynamoDBLocalClientFactory.startSingletonServer();
    try {
      dynamoDB = new DynamoDBMSContract().getMetadataStore().getDynamoDB();
    } catch (AmazonServiceException e) {
      final String msg = "Cannot initialize a DynamoDBMetadataStore instance "
          + "against the local DynamoDB server. Perhaps the DynamoDBLocal "
          + "server is not configured correctly. ";
      LOG.error(msg, e);
      // fail fast if the DynamoDBLocal server can not work
      fail(msg + e.getMessage());
    }
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    if (dynamoDB != null) {
      dynamoDB.shutdown();
    }
    DynamoDBLocalClientFactory.stopSingletonServer();
  }

  /**
   * Each contract has its own S3AFileSystem and DynamoDBMetadataStore objects.
   */
  private static class DynamoDBMSContract extends AbstractMSContract {
    private final S3AFileSystem s3afs;
    private final DynamoDBMetadataStore ms = new DynamoDBMetadataStore();

    DynamoDBMSContract() throws IOException {
      final Configuration conf = new Configuration();
      // using mocked S3 clients
      conf.setClass(S3_CLIENT_FACTORY_IMPL, MockS3ClientFactory.class,
          S3ClientFactory.class);
      conf.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY, S3URI);
      // setting config for creating a DynamoDBClient against local server
      conf.set(Constants.ACCESS_KEY, "dummy-access-key");
      conf.set(Constants.SECRET_KEY, "dummy-secret-key");
      conf.setBoolean(Constants.S3GUARD_DDB_TABLE_CREATE_KEY, true);
      conf.setClass(S3Guard.S3GUARD_DDB_CLIENT_FACTORY_IMPL,
          DynamoDBLocalClientFactory.class, DynamoDBClientFactory.class);

      // always create new file system object for a test contract
      s3afs = (S3AFileSystem) FileSystem.newInstance(conf);
      ms.initialize(s3afs);
    }

    @Override
    public S3AFileSystem getFileSystem() {
      return s3afs;
    }

    @Override
    public DynamoDBMetadataStore getMetadataStore() {
      return ms;
    }
  }

  @Override
  public DynamoDBMSContract createContract() throws IOException {
    return new DynamoDBMSContract();
  }

  @Override
  FileStatus basicFileStatus(Path path, int size, boolean isDir)
      throws IOException {
    String owner = UserGroupInformation.getCurrentUser().getShortUserName();
    return isDir
        ? new S3AFileStatus(true, path, owner)
        : new S3AFileStatus(size, getModTime(), path, BLOCK_SIZE, owner);
  }

  private DynamoDBMetadataStore getDynamoMetadataStore() throws IOException {
    return (DynamoDBMetadataStore) getContract().getMetadataStore();
  }

  private S3AFileSystem getFileSystem() {
    return (S3AFileSystem) getContract().getFileSystem();
  }

  /**
   * This tests that after initialize() using an S3AFileSystem object, the
   * instance should have been initialized successfully, and tables are ACTIVE.
   */
  @Test
  public void testInitialize() throws IOException {
    final String tableName = "testInitializeWithFileSystem";
    final S3AFileSystem s3afs = getFileSystem();
    final Configuration conf = s3afs.getConf();
    conf.set(Constants.S3GUARD_DDB_TABLE_NAME_KEY, tableName);
    try (DynamoDBMetadataStore ddbms = new DynamoDBMetadataStore()) {
      ddbms.initialize(s3afs);
      verifyTableInitialized(tableName);
      assertNotNull(ddbms.getTable());
      assertEquals(tableName, ddbms.getTable().getTableName());
      String expectedRegion = conf.get(Constants.S3GUARD_DDB_REGION_KEY,
          s3afs.getBucketLocation(tableName));
      assertEquals("DynamoDB table should be in configured region or the same" +
              " region as S3 bucket",
          expectedRegion,
          ddbms.getRegion());
    }
  }

  /**
   * This tests that after initialize() using a Configuration object, the
   * instance should have been initialized successfully, and tables are ACTIVE.
   */
  @Test
  public void testInitializeWithConfiguration() throws IOException {
    final String tableName = "testInitializeWithConfiguration";
    final Configuration conf = getFileSystem().getConf();
    conf.unset(Constants.S3GUARD_DDB_TABLE_NAME_KEY);
    String savedRegion = conf.get(Constants.S3GUARD_DDB_REGION_KEY,
        getFileSystem().getBucketLocation());
    conf.unset(Constants.S3GUARD_DDB_REGION_KEY);
    try {
      DynamoDBMetadataStore ddbms = new DynamoDBMetadataStore();
      ddbms.initialize(conf);
      fail("Should have failed because the table name is not set!");
    } catch (IllegalArgumentException ignored) {
    }
    // config table name
    conf.set(Constants.S3GUARD_DDB_TABLE_NAME_KEY, tableName);
    try {
      DynamoDBMetadataStore ddbms = new DynamoDBMetadataStore();
      ddbms.initialize(conf);
      fail("Should have failed because as the region is not set!");
    } catch (IllegalArgumentException ignored) {
    }
    // config region
    conf.set(Constants.S3GUARD_DDB_REGION_KEY, savedRegion);
    try (DynamoDBMetadataStore ddbms = new DynamoDBMetadataStore()) {
      ddbms.initialize(conf);
      verifyTableInitialized(tableName);
      assertNotNull(ddbms.getTable());
      assertEquals(tableName, ddbms.getTable().getTableName());
      assertEquals("Unexpected key schema found!",
          keySchema(),
          ddbms.getTable().describe().getKeySchema());
    }
  }

  /**
   * Test that for a large batch write request, the limit is handled correctly.
   */
  @Test
  public void testBatchWrite() throws IOException {
    final int[] numMetasToDeleteOrPut = {
        -1, // null
        0, // empty collection
        1, // one path
        S3GUARD_DDB_BATCH_WRITE_REQUEST_LIMIT, // exact limit of a batch request
        S3GUARD_DDB_BATCH_WRITE_REQUEST_LIMIT + 1 // limit + 1
    };
    for (int numOldMetas : numMetasToDeleteOrPut) {
      for (int numNewMetas : numMetasToDeleteOrPut) {
        doTestBatchWrite(numOldMetas, numNewMetas);
      }
    }
  }

  private void doTestBatchWrite(int numDelete, int numPut) throws IOException {
    final String root = S3URI + "/testBatchWrite_" + numDelete + '_' + numPut;
    final Path oldDir = new Path(root, "oldDir");
    final Path newDir = new Path(root, "newDir");
    LOG.info("doTestBatchWrite: oldDir={}, newDir={}", oldDir, newDir);

    DynamoDBMetadataStore ms = getDynamoMetadataStore();
    ms.put(new PathMetadata(basicFileStatus(oldDir, 0, true)));
    ms.put(new PathMetadata(basicFileStatus(newDir, 0, true)));

    final Collection<PathMetadata> oldMetas =
        numDelete < 0 ? null : new ArrayList<PathMetadata>(numDelete);
    for (int i = 0; i < numDelete; i++) {
      oldMetas.add(new PathMetadata(
          basicFileStatus(new Path(oldDir, "child" + i), i, true)));
    }
    final Collection<PathMetadata> newMetas =
        numPut < 0 ? null : new ArrayList<PathMetadata>(numPut);
    for (int i = 0; i < numPut; i++) {
      newMetas.add(new PathMetadata(
          basicFileStatus(new Path(newDir, "child" + i), i, false)));
    }

    Collection<Path> pathsToDelete = null;
    if (oldMetas != null) {
      // put all metadata of old paths and verify
      ms.put(new DirListingMetadata(oldDir, oldMetas, false));
      assertEquals(0, ms.listChildren(newDir).numEntries());
      assertTrue(CollectionUtils.isEqualCollection(oldMetas,
          ms.listChildren(oldDir).getListing()));

      pathsToDelete = new ArrayList<>(oldMetas.size());
      for (PathMetadata meta : oldMetas) {
        pathsToDelete.add(meta.getFileStatus().getPath());
      }
    }

    // move the old paths to new paths and verify
    ms.move(pathsToDelete, newMetas);
    assertEquals(0, ms.listChildren(oldDir).numEntries());
    if (newMetas != null) {
      assertTrue(CollectionUtils.isEqualCollection(newMetas,
          ms.listChildren(newDir).getListing()));
    }
  }

  @Test
  public void testInitExistingTable() throws IOException {
    final DynamoDBMetadataStore ddbms = getDynamoMetadataStore();
    final String tableName = ddbms.getTable().getTableName();
    verifyTableInitialized(tableName);
    // create existing table
    ddbms.initTable();
    verifyTableInitialized(tableName);
  }

  /**
   * Test the low level version check code.
   */
  @Test
  public void testItemVersionCompatibility() throws Throwable {
    verifyVersionCompatibility("table",
        createVersionMarker(VERSION_MARKER, VERSION, 0));
  }

  /**
   * Test that a version marker entry without the version number field
   * is rejected as incompatible with a meaningful error message.
   */
  @Test
  public void testItemLacksVersion() throws Throwable {
    boolean exceptionThrown = false;
    try {
      verifyVersionCompatibility("table",
          new Item().withPrimaryKey(
              createVersionMarkerPrimaryKey(VERSION_MARKER)));
    } catch (IOException e) {
      exceptionThrown = true;
      String message = e.getMessage();
      assertTrue("Expected " + E_NOT_VERSION_MARKER +", got " + message,
          message.contains(E_NOT_VERSION_MARKER));
    }
    assertTrue("Expected an exception but none was thrown", exceptionThrown);
  }

  /**
   * Delete the version marker and verify that table init fails.
   */
  @Test
  public void testTableVersionRequired() throws Exception {
    final DynamoDBMetadataStore ddbms = createContract().getMetadataStore();
    String tableName = getFileSystem().getConf().get(Constants
        .S3GUARD_DDB_TABLE_NAME_KEY, BUCKET);
    Table table = verifyTableInitialized(tableName);
    table.deleteItem(VERSION_MARKER_PRIMARY_KEY);

    boolean exceptionThrown = false;
    try {
      // create existing table
      ddbms.initTable();
    } catch (IOException e) {
      exceptionThrown = true;
      String message = e.getMessage();
      assertTrue("Expected " + E_NO_VERSION_MARKER +", got " + message,
          message.contains(E_NO_VERSION_MARKER));
    }
    assertTrue("Expected an exception but none was thrown", exceptionThrown);
  }

  /**
   * Set the version value to a different number and verify that
   * table init fails.
   */
  @Test
  public void testTableVersionMismatch() throws Exception {
    final DynamoDBMetadataStore ddbms = createContract().getMetadataStore();
    String tableName = getFileSystem().getConf().get(Constants
        .S3GUARD_DDB_TABLE_NAME_KEY, BUCKET);
    Table table = verifyTableInitialized(tableName);
    table.deleteItem(VERSION_MARKER_PRIMARY_KEY);
    Item v200 = createVersionMarker(VERSION_MARKER, 200, 0);
    table.putItem(v200);

    boolean exceptionThrown = false;
    try {
      // create existing table
      ddbms.initTable();
    } catch (IOException e) {
      exceptionThrown = true;
      String message = e.getMessage();
      assertTrue("Expected " + E_INCOMPATIBLE_VERSION +", got " + message,
          message.contains(E_INCOMPATIBLE_VERSION));
    }
    assertTrue("Expected an exception but none was thrown", exceptionThrown);
  }

  /**
   * Test that initTable fails with IOException when table does not exist and
   * table auto-creation is disabled.
   */
  @Test
  public void testFailNonexistentTable() throws IOException {
    final String tableName = "testFailNonexistentTable";
    final S3AFileSystem s3afs = getFileSystem();
    final Configuration conf = s3afs.getConf();
    conf.set(Constants.S3GUARD_DDB_TABLE_NAME_KEY, tableName);
    conf.unset(Constants.S3GUARD_DDB_TABLE_CREATE_KEY);
    try {
      final DynamoDBMetadataStore ddbms = new DynamoDBMetadataStore();
      ddbms.initialize(s3afs);
      fail("Should have failed as table does not exist and table auto-creation "
          + "is disabled");
    } catch (IOException ignored) {
    }
  }

  /**
   * Test cases about root directory as it is not in the DynamoDB table.
   */
  @Test
  public void testRootDirectory() throws IOException {
    final DynamoDBMetadataStore ddbms = getDynamoMetadataStore();
    Path rootPath = new Path(S3URI);
    verifyRootDirectory(ddbms.get(rootPath), true);

    ddbms.put(new PathMetadata(new S3AFileStatus(true,
        new Path(rootPath, "foo"),
        UserGroupInformation.getCurrentUser().getShortUserName())));
    verifyRootDirectory(ddbms.get(new Path(S3URI)), false);
  }

  private void verifyRootDirectory(PathMetadata rootMeta, boolean isEmpty) {
    assertNotNull(rootMeta);
    final FileStatus status = rootMeta.getFileStatus();
    assertNotNull(status);
    assertTrue(status.isDirectory());
    // UNKNOWN is always a valid option, but true / false should not contradict
    if (isEmpty) {
      assertTrue("Should not be marked non-empty",
          rootMeta.isEmptyDirectory() != Tristate.FALSE);
    } else {
      assertTrue("Should not be marked empty",
          rootMeta.isEmptyDirectory() != Tristate.TRUE);
    }
  }

  @Test
  public void testProvisionTable() throws IOException {
    final DynamoDBMetadataStore ddbms = getDynamoMetadataStore();
    final String tableName = ddbms.getTable().getTableName();
    final ProvisionedThroughputDescription oldProvision =
        dynamoDB.getTable(tableName).describe().getProvisionedThroughput();
    ddbms.provisionTable(oldProvision.getReadCapacityUnits() * 2,
        oldProvision.getWriteCapacityUnits() * 2);
    final ProvisionedThroughputDescription newProvision =
        dynamoDB.getTable(tableName).describe().getProvisionedThroughput();
    LOG.info("Old provision = {}, new provision = {}",
        oldProvision, newProvision);
    assertEquals(oldProvision.getReadCapacityUnits() * 2,
        newProvision.getReadCapacityUnits().longValue());
    assertEquals(oldProvision.getWriteCapacityUnits() * 2,
        newProvision.getWriteCapacityUnits().longValue());
  }

  @Test
  public void testDeleteTable() throws IOException {
    final String tableName = "testDeleteTable";
    final S3AFileSystem s3afs = getFileSystem();
    final Configuration conf = s3afs.getConf();
    conf.set(Constants.S3GUARD_DDB_TABLE_NAME_KEY, tableName);
    try (DynamoDBMetadataStore ddbms = new DynamoDBMetadataStore()) {
      ddbms.initialize(s3afs);
      // we can list the empty table
      ddbms.listChildren(new Path(S3URI));

      ddbms.destroy();
      verifyTableNotExist(tableName);

      // delete table once more; be ResourceNotFoundException swallowed silently
      ddbms.destroy();
      verifyTableNotExist(tableName);

      try {
        // we can no longer list the destroyed table
        ddbms.listChildren(new Path(S3URI));
        fail("Should have failed after the table is destroyed!");
      } catch (IOException ignored) {
      }
    }
  }

  /**
   * This validates the table is created and ACTIVE in DynamoDB.
   *
   * This should not rely on the {@link DynamoDBMetadataStore} implementation.
   * Return the table
   */
  private static Table verifyTableInitialized(String tableName) {
    final Table table = dynamoDB.getTable(tableName);
    final TableDescription td = table.describe();
    assertEquals(tableName, td.getTableName());
    assertEquals("ACTIVE", td.getTableStatus());
    return table;
  }

  /**
   * This validates the table is not found in DynamoDB.
   *
   * This should not rely on the {@link DynamoDBMetadataStore} implementation.
   */
  private static void verifyTableNotExist(String tableName) {
    final Table table = dynamoDB.getTable(tableName);
    try {
      table.describe();
      fail("Expecting ResourceNotFoundException for table '" + tableName + "'");
    } catch (ResourceNotFoundException ignored) {
    }
  }

}
