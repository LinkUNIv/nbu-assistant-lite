package app.nbult.lite.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AccountStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("school_account", Context.MODE_PRIVATE)
    private val alias = "school_account_aes_v1"
    private fun key(): SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun credentials(): Pair<String,String>? {
        val data=prefs.getString("cipher",null) ?: return null
        val iv=prefs.getString("iv",null) ?: return null
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))
        val obj=JSONObject(String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),Charsets.UTF_8))
        return obj.getString("account") to obj.getString("password")
    }
    fun save(account:String,password:String) {
        require(account.isNotBlank() && password.isNotEmpty())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val value=JSONObject().put("account",account).put("password",password).toString().toByteArray(Charsets.UTF_8)
        val encrypted=try { cipher.doFinal(value) } finally { value.fill(0) }
        check(prefs.edit().putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .putString("cipher",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit())
    }
    fun clear() {
        check(prefs.edit().clear().commit())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if(containsAlias(alias))deleteEntry(alias) }
    }
}
