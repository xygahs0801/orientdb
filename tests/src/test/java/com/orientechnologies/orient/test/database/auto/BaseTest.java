package com.orientechnologies.orient.test.database.auto;

import com.orientechnologies.orient.client.db.ODatabaseHelper;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.object.db.OObjectDatabaseTx;
import org.testng.annotations.*;

import java.io.IOException;

@Test
public abstract class BaseTest<T extends ODatabase> {
  protected T      database;
  protected String url;
  private boolean  dropDb             = false;
  private String   storageType;
  private boolean  autoManageDatabase = true;

  protected BaseTest() {
  }

  @Parameters(value = "url")
  public BaseTest(@Optional String url) {
    String config = System.getProperty("orientdb.test.env");
    if ("ci".equals(config) || "release".equals(config))
      storageType = "plocal";
    else
      storageType = System.getProperty("storageType");

    if (storageType == null)
      storageType = "memory";

    if (url == null) {
      if ("remote".equals(storageType)) {
        url = getStorageType() + ":localhost/demo";
        dropDb = true;
      } else {
        final String buildDirectory = System.getProperty("buildDirectory", ".");
        url = getStorageType() + ":" + buildDirectory + "/test-db/demo";
        dropDb = true;
      }
    }

    if (url.startsWith("memory:") && !url.startsWith("remote")) {
      final ODatabaseDocumentTx db = new ODatabaseDocumentTx(url);
      if (!db.exists())
        db.create().close();
    }

    this.url = url;
  }

  @Parameters(value = "url")
  public BaseTest(@Optional String url, String prefix) {
    if (url == null) {
      final String buildDirectory = System.getProperty("buildDirectory", ".");
      url = getStorageType() + ":" + buildDirectory + "/test-db/demo" + prefix;
      dropDb = true;
    } else
      url = url + prefix;

    this.url = url;
  }

  @BeforeClass
  public void beforeClass() throws Exception {
    database = createDatabaseInstance(url);
    this.url = database.getURL();

    String remoteStorageType = storageType;

    if (dropDb) {
      if (storageType.equals("remote"))
        remoteStorageType = "plocal";

      if (ODatabaseHelper.existsDatabase(database, remoteStorageType)) {
        ODatabaseHelper.openDatabase(database);
        ODatabaseHelper.dropDatabase(database, remoteStorageType);
      }

      createDatabase();
    }

    ODatabaseHelper.openDatabase(database);
  }

  @AfterClass
  public void afterClass() throws Exception {
    if (!autoManageDatabase)
      return;

    if (dropDb) {
      if (database.isClosed())
        ODatabaseHelper.openDatabase(database);

      String remoteStorageType = storageType;
      if (storageType.equals("remote"))
        remoteStorageType = "plocal";

      ODatabaseHelper.dropDatabase(database, remoteStorageType);
    } else {
      if (!database.isClosed()) {
        database.activateOnCurrentThread();
        database.close();
      }
    }
  }

  @BeforeMethod
  public void beforeMethod() throws Exception {
    if (!autoManageDatabase)
      return;
    database.activateOnCurrentThread();
    if (database.isClosed())
      ODatabaseHelper.openDatabase(database);
  }

  @AfterMethod
  public void afterMethod() throws Exception {
    if (!autoManageDatabase)
      return;

    if (!database.isClosed()) {
      database.activateOnCurrentThread();
      database.close();
    }
  }

  protected abstract T createDatabaseInstance(String url);

  protected void createDatabase() throws IOException {
    ODatabaseHelper.createDatabase(database, database.getURL());
  }

  protected String getTestEnv(){
    return System.getProperty("orientdb.test.env");
  }

  protected final String getStorageType() {
    return storageType;
  }

  protected void createBasicTestSchema() {
    ODatabase database = this.database;
    if (database instanceof OObjectDatabaseTx)
      database = ((OObjectDatabaseTx) database).getUnderlying();

    if (database.getMetadata().getSchema().existsClass("Whiz"))
      return;

    database.addCluster("csv");
    database.addCluster("flat");
    database.addCluster("binary");
//    database.addBlobCluster("blobCluster");


    OClass account = database.getMetadata().getSchema().createClass("Account", 1, null);
    account.createProperty("id", OType.INTEGER);
    account.createProperty("birthDate", OType.DATE);
    account.createProperty("binary", OType.BINARY);

    database.getMetadata().getSchema().createClass("Company", account);

    OClass profile = database.getMetadata().getSchema().createClass("Profile", 1, null);
    profile.createProperty("nick", OType.STRING).setMin("3").setMax("30").createIndex(OClass.INDEX_TYPE.UNIQUE);
    profile.createProperty("name", OType.STRING).setMin("3").setMax("30").createIndex(OClass.INDEX_TYPE.NOTUNIQUE);
    profile.createProperty("surname", OType.STRING).setMin("3").setMax("30");
    profile.createProperty("registeredOn", OType.DATETIME).setMin("2010-01-01 00:00:00");
    profile.createProperty("lastAccessOn", OType.DATETIME).setMin("2010-01-01 00:00:00");
    profile.createProperty("photo", OType.TRANSIENT);

    OClass whiz = database.getMetadata().getSchema().createClass("Whiz", 1, null);
    whiz.createProperty("id", OType.INTEGER);
    whiz.createProperty("account", OType.LINK, account);
    whiz.createProperty("date", OType.DATE).setMin("2010-01-01");
    whiz.createProperty("text", OType.STRING).setMandatory(true).setMin("1").setMax("140").createIndex(OClass.INDEX_TYPE.FULLTEXT);
    whiz.createProperty("replyTo", OType.LINK, account);

    OClass strictTest = database.getMetadata().getSchema().createClass("StrictTest", 1, null);
    strictTest.setStrictMode(true);
    strictTest.createProperty("id", OType.INTEGER).isMandatory();
    strictTest.createProperty("name", OType.STRING);

    OClass animalRace = database.getMetadata().getSchema().createClass("AnimalRace", 1, null);
    animalRace.createProperty("name", OType.STRING);

    OClass animal = database.getMetadata().getSchema().createClass("Animal", 1, null);
    animal.createProperty("races", OType.LINKSET, animalRace);
    animal.createProperty("name", OType.STRING);
  }

  protected boolean isAutoManageDatabase() {
    return autoManageDatabase;
  }

  protected void setAutoManageDatabase(final boolean autoManageDatabase) {
    this.autoManageDatabase = autoManageDatabase;
  }

  protected boolean isDropDb() {
    return dropDb;
  }

  protected void setDropDb(final boolean dropDb) {
    this.dropDb = dropDb;
  }

  protected boolean skipTestIfRemote() {
    final OStorage stg = ((ODatabaseDocumentTx) database).getStorage().getUnderlying();

    if (!(stg instanceof OAbstractPaginatedStorage))
      // ONLY PLOCAL AND MEMORY STORAGES SUPPORTED
      return true;
    return false;
  }
}
