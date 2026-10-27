package com.offerpoint.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalSecurityTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val alias="offerpoint.test.${UUID.randomUUID()}";private val name="security-${UUID.randomUUID()}.db"
    private lateinit var db:PosDatabase;private lateinit var repo:RoomSecurityRepository;private var now=1_000L
    @Before fun before()=runBlocking {db=Room.databaseBuilder(context,PosDatabase::class.java,name).build();RoomShopRepository(db,false).initialize();repo=RoomSecurityRepository(db,alias){now}}
    @After fun after(){db.close();context.deleteDatabase(name);KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}}
    private suspend fun failure(block:suspend()->Unit){try{block()}catch(_:Exception){return};throw AssertionError("Expected failure")}

    @Test fun pinIsOptionalThenStoredAsSaltedDeviceBoundHashAndSurvivesReopening()=runBlocking {
        assertFalse(repo.observe().first().enabled);assertTrue(repo.verify(charArrayOf()))
        repo.configure(charArrayOf(),"123456".toCharArray(),2)
        val row=requireNotNull(db.posDao().getSecurity());assertNotEquals("123456",row.pinHash);assertNotNull(row.salt)
        assertTrue(repo.observe().first().enabled);assertTrue(repo.verify("123456".toCharArray()));assertFalse(repo.verify("999999".toCharArray()))
        db.close();db=Room.databaseBuilder(context,PosDatabase::class.java,name).build();repo=RoomSecurityRepository(db,alias){now}
        assertTrue(repo.observe().first().enabled);assertTrue(repo.verify("123456".toCharArray()))
    }
    @Test fun fiveWrongAttemptsPersistLockoutAndCorrectPinOnlyWorksAfterItsDeadline()=runBlocking {
        repo.configure(charArrayOf(),"123456".toCharArray(),2);repeat(5){assertFalse(repo.verify("000000".toCharArray()))}
        val locked=repo.observe().first();assertEquals(5,locked.failedAttempts);assertEquals(61_000L,locked.lockedUntil)
        db.close();db=Room.databaseBuilder(context,PosDatabase::class.java,name).build();repo=RoomSecurityRepository(db,alias){now}
        failure{repo.verify("123456".toCharArray())};now=61_000;assertTrue(repo.verify("123456".toCharArray()));assertEquals(0,repo.observe().first().failedAttempts)
    }
    @Test fun changingOrDisablingPinRequiresTheExistingPin()=runBlocking {
        repo.configure(charArrayOf(),"123456".toCharArray(),2)
        failure{repo.configure("000000".toCharArray(),null,2)};assertTrue(repo.observe().first().enabled)
        repo.configure("123456".toCharArray(),"654321".toCharArray(),5)
        assertFalse(repo.verify("123456".toCharArray()));assertTrue(repo.verify("654321".toCharArray()));assertEquals(5,repo.observe().first().autoLockMinutes)
        repo.configure("654321".toCharArray(),null,5);assertFalse(repo.observe().first().enabled)
    }
    @Test fun pinAndTimeoutValidationDoesNotCreateCredentials()=runBlocking {
        failure{repo.configure(charArrayOf(),"12345".toCharArray(),2)};failure{repo.configure(charArrayOf(),"12345A".toCharArray(),2)}
        failure{repo.configure(charArrayOf(),"123456".toCharArray(),0)};failure{repo.configure(charArrayOf(),"123456".toCharArray(),31)}
        assertNull(db.posDao().getSecurity())
    }
    @Test fun deviceCredentialRecoveryDisablesPinAndRecordsAnAuditWithoutSecrets()=runBlocking {
        repo.configure(charArrayOf(),"123456".toCharArray(),2);repo.resetAfterDeviceCredential()
        assertFalse(repo.observe().first().enabled);assertTrue(db.posDao().getAudits().any{it.action=="RESET_PIN_AFTER_DEVICE_CREDENTIAL"})
        assertFalse(db.posDao().getAudits().toString().contains("123456"))
    }
}
