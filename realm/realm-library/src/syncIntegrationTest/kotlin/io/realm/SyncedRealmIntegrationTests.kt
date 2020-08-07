/*
 * Copyright 2020 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.realm

import android.os.SystemClock
import android.util.Log
import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.realm.entities.DefaultSyncSchema
import io.realm.entities.SyncDog
import io.realm.entities.SyncStringOnly
import io.realm.exceptions.DownloadingRealmInterruptedException
import io.realm.exceptions.RealmMigrationNeededException
import io.realm.internal.OsRealmConfig
import io.realm.kotlin.syncSession
import io.realm.log.LogLevel
import io.realm.log.RealmLog
import io.realm.log.RealmLogger
import io.realm.mongodb.*
import io.realm.mongodb.sync.*
import io.realm.rule.BlockingLooperThread
import io.realm.util.assertFailsWithErrorCode
import org.bson.BsonObjectId
import org.bson.types.ObjectId
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val SECRET_PASSWORD = "123456"

/**
 * Catch all class for tests that not naturally fit anywhere else.
 */
@RunWith(AndroidJUnit4::class)
class SyncedRealmIntegrationTests {

    private val looperThread = BlockingLooperThread()

    private lateinit var app: App
    private lateinit var user: User
    private lateinit var syncConfiguration: SyncConfiguration

    private val configurationFactory: TestSyncConfigurationFactory = TestSyncConfigurationFactory()

    @Before
    fun setup() {
        Realm.init(InstrumentationRegistry.getInstrumentation().targetContext)
        RealmLog.setLevel(LogLevel.ALL)
        app = TestApp()
        user = app.registerUserAndLogin(TestHelper.getRandomEmail(), SECRET_PASSWORD)
        syncConfiguration = configurationFactory
                // TODO We generate new partition value for each test to avoid overlaps in data. We
                //  could make test booting with a cleaner state by somehow flushing data between
                //  tests.
                .createSyncConfigurationBuilder(user, BsonObjectId(ObjectId()))
                .modules(DefaultSyncSchema())
                .build()
    }

    @After
    fun teardown() {
        if (this::app.isInitialized) {
            app.close()
        }
        RealmLog.setLevel(LogLevel.WARN)
    }

    @Test
    fun loginLogoutResumeSyncing() = looperThread.runBlocking {
        val config: SyncConfiguration = configurationFactory.createSyncConfigurationBuilder(user, user.id)
                .testSchema(SyncStringOnly::class.java)
                .testSessionStopPolicy(OsRealmConfig.SyncSessionStopPolicy.IMMEDIATELY)
                .build()
        Realm.getInstance(config).use { realm ->
            realm.executeTransaction {
                realm.createObject(SyncStringOnly::class.java, ObjectId()).chars = "Foo"
            }
            realm.syncSession.uploadAllLocalChanges()
            user.logOut()
        }
        try {
            assertTrue(Realm.deleteRealm(config))
        } catch (e: IllegalStateException) {
            // FIXME: We don't have a way to ensure that the Realm instance on client thread has been
            //  closed for now https://github.com/realm/realm-java/issues/5416
            if (e.message!!.contains("It's not allowed to delete the file")) {
                // retry after 1 second
                SystemClock.sleep(1000)
                assertTrue(Realm.deleteRealm(config))
            }
        }

        // FIXME Is this sufficient to test "loginLogoutResumeSynching"-case
        user = app.login(Credentials.emailPassword(user.email, SECRET_PASSWORD))
        val config2: SyncConfiguration = configurationFactory.createSyncConfigurationBuilder(user, user.id)
                .testSchema(SyncStringOnly::class.java)
                .build()
        Realm.getInstance(config2).use { realm ->
            realm.syncSession.downloadAllServerChanges()
            realm.refresh()
            assertEquals(1, realm.where(SyncStringOnly::class.java).count())
        }
        looperThread.testComplete()
    }

    @Test
    @UiThreadTest
    fun waitForInitialRemoteData_mainThreadThrows() {
        val user: User = SyncTestUtils.createTestUser(app)
        val config: SyncConfiguration = configurationFactory.createSyncConfigurationBuilder(user, user.id)
                .waitForInitialRemoteData()
                .build()
        assertFailsWith<java.lang.IllegalStateException> {
            Realm.getInstance(config).close()
        }
    }

    @Test
    fun waitForInitialRemoteData() {
        // 1. Copy a valid Realm to the server (and pray it does it within 10 seconds)
        val configOld: SyncConfiguration = configurationFactory.createSyncConfigurationBuilder(user, user.id)
                .testSchema(SyncStringOnly::class.java)
                .testSessionStopPolicy(OsRealmConfig.SyncSessionStopPolicy.IMMEDIATELY)
                .build()
        Realm.getInstance(configOld).use { realm ->
            realm.executeTransaction { realm ->
                for (i in 0..9) {
                    realm.createObject(SyncStringOnly::class.java, ObjectId()).chars = "Foo$i"
                }
            }
            realm.syncSession.uploadAllLocalChanges()
        }
        user.logOut()

        // 2. Local state should now be completely reset. Open the same sync Realm but different local name again with
        // a new configuration which should download the uploaded changes (pray it managed to do so within the time frame).
        // Use different user to trigger different path
        val user2 = app.registerUserAndLogin(TestHelper.getRandomEmail(), SECRET_PASSWORD)
        val config: SyncConfiguration = configurationFactory.createSyncConfigurationBuilder(user2, user.id)
                .testSchema(SyncStringOnly::class.java)
                .waitForInitialRemoteData()
                .build()
        Realm.getInstance(config).use { realm ->
            realm.executeTransaction { realm ->
                for (i in 0..9) {
                    realm.createObject(SyncStringOnly::class.java, ObjectId()).chars = "Foo 1$i"
                }
            }
            assertEquals(20, realm.where(SyncStringOnly::class.java).count())
        }
    }

    // This tests will start and cancel getting a Realm 10 times. The Realm should be resilient towards that
    // We cannot do much better since we cannot control the order of events internally in Realm which would be
    // needed to correctly test all error paths.
    @Test
    @Ignore("Sync somehow keeps a Realm alive, causing the Realm.deleteRealm to throw " +
            " https://github.com/realm/realm-java/issues/5416")
    fun waitForInitialData_resilientInCaseOfRetries() {
        val config: SyncConfiguration = configurationFactory.createSyncConfigurationBuilder(user, user.id)
                .waitForInitialRemoteData()
                .build()
        for (i in 0..9) {
            val t = Thread(Runnable {
                var realm: Realm? = null
                assertFailsWith<DownloadingRealmInterruptedException> {
                    Thread.currentThread().interrupt()
                    Realm.getInstance(config).close()
                }
                // FIXME Seems like the file is actually created before interrupted. Is this check
                //  correct?
                 assertFalse(File(config.getPath()).exists())
                // FIXME This can throw IllegalStateException as the realm is maybe not closed
                //  properly due to https://github.com/realm/realm-java/issues/5416
                Realm.deleteRealm(config)
            })
            t.start()
            t.join()
        }
    }

    // This tests will start and cancel getting a Realm 10 times. The Realm should be resilient towards that
    // We cannot do much better since we cannot control the order of events internally in Realm which would be
    // needed to correctly test all error paths.
    @Test
    // FIXME This does not throw anymore as described in issue. But do the test still make sense
    //  with new sync?
    //@Ignore("See https://github.com/realm/realm-java/issues/5373")
    fun waitForInitialData_resilientInCaseOfRetriesAsync() = looperThread.runBlocking {
        val config: SyncConfiguration = configurationFactory.createSyncConfigurationBuilder(user, user.id)
                .testSessionStopPolicy(OsRealmConfig.SyncSessionStopPolicy.IMMEDIATELY)
                .waitForInitialRemoteData()
                .build()
        val randomizer = Random()
        for (i in 0..9) {
            val task = Realm.getInstanceAsync(config, object : Realm.Callback() {
                override fun onSuccess(realm: Realm) { fail() }
                override fun onError(exception: Throwable) { fail(exception.toString()) }
            })
            SystemClock.sleep(randomizer.nextInt(5).toLong())
            task.cancel()
        }
        // Leave some time for the async callbacks to actually get through
        looperThread.postRunnableDelayed(
                Runnable { looperThread.testComplete() },
                1000
        )
    }

    @Test
    fun waitForInitialRemoteData_readOnlyTrue() {
        // 1. Copy a valid Realm to the server (and pray it does it within 10 seconds)
        val configOld: SyncConfiguration = configurationFactory.createSyncConfigurationBuilder(user, user.id)
                .testSchema(SyncStringOnly::class.java)
                .build()
        Realm.getInstance(configOld).use { realm ->
            realm.executeTransaction { realm ->
                for (i in 0..9) {
                    realm.createObject(SyncStringOnly::class.java, ObjectId()).chars = "Foo$i"
                }
            }
            realm.syncSession.uploadAllLocalChanges()
        }
        user.logOut()

        // 2. Local state should now be completely reset. Open the Realm again with a new configuration which should
        // download the uploaded changes (pray it managed to do so within the time frame).
        // Use different user to trigger different path
        val user2 = app.registerUserAndLogin(TestHelper.getRandomEmail(), SECRET_PASSWORD)
        val configNew: SyncConfiguration = configurationFactory.createSyncConfigurationBuilder(user2, user.id)
                .waitForInitialRemoteData()
                .readOnly()
                .testSchema(SyncStringOnly::class.java)
                .build()
        assertFalse(configNew.testRealmExists())
        Realm.getInstance(configNew).use { realm ->
            assertEquals(10, realm.where(SyncStringOnly::class.java).count())
        }
        user.logOut()
    }

    @Test
    // FIXME
    @Ignore("Not really sure how to do this test with new sync")
    fun waitForInitialRemoteData_readOnlyTrue_throwsIfWrongServerSchema() {
        val configNew: SyncConfiguration = configurationFactory.createSyncConfigurationBuilder(user, user.id)
                .waitForInitialRemoteData()
                .readOnly()
                .testSchema(SyncStringOnly::class.java)
                .build()
        assertFalse(configNew.testRealmExists())
        assertFailsWith<RealmMigrationNeededException> {
            // This will fail, because the server Realm is completely empty and the Client is not allowed to write the
            // schema.
            // FIXME Does not throw. How to test schema migration with new sync when server is in dev mode
            Realm.getInstance(configNew).close()
        }
        user.logOut()
    }

    @Test
    fun waitForInitialRemoteData_readOnlyFalse_upgradeSchema() {
        val config: SyncConfiguration = configurationFactory.createSyncConfigurationBuilder(user, user.id)
                .waitForInitialRemoteData() // Not readonly so Client should be allowed to write schema
                .testSchema(SyncStringOnly::class.java) // This schema should be written when opening the empty Realm.
                .schemaVersion(2)
                .build()
        assertFalse(config.testRealmExists())
        Realm.getInstance(config).use { realm ->
            assertEquals(0, realm.where(SyncStringOnly::class.java).count())
        }
        user.logOut()
    }

    @Test
    fun defaultRealm() {
        val config: SyncConfiguration = SyncConfiguration.defaultConfig(user, user.id)
        Realm.getInstance(config).use { realm ->
            realm.syncSession.downloadAllServerChanges()
            realm.refresh()
            assertTrue(realm.isEmpty)
        }
        user.logOut()
    }


}
