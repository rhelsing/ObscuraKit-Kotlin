package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.network.APIClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The definitive cross-platform question: does /v1/sessions/refresh preserve
 * DEVICE scope for a device session's refresh token?
 *
 * iOS shows `refresh scope before=true after=false` — the refresh downgrades a
 * device-scoped token to user-scoped, which 403s the gateway ticket. This runs
 * the SAME check against the SAME server on the working (Android) kit:
 *   - after != null  → refresh preserves scope → iOS holds a wrong (user-scoped)
 *                      refresh token; fix is on the iOS issuing side.
 *   - after == null  → refresh drops scope for everyone → server behavior; both
 *                      platforms must re-establish device scope after a refresh.
 */
class RefreshScopeTests {

    @Test
    fun `refresh preserves device scope`() = runBlocking {
        assumeTrue(checkServer())

        val client = ObscuraClient(ObscuraConfig(API))
        client.register(uniqueName("scope"), TEST_PASSWORD)

        val api = APIClient(API)
        val beforeDeviceId = api.getDeviceId(client.token)
        assertNotNull(beforeDeviceId, "token should be device-scoped after register")

        val rt = requireNotNull(client.refreshToken) { "register should yield a refresh token" }
        val result = api.refreshSession(rt)
        val afterDeviceId = api.getDeviceId(result.token)

        println("[scope] before=$beforeDeviceId after=$afterDeviceId")
        assertNotNull(afterDeviceId, "refresh must KEEP the token device-scoped")
    }
}
