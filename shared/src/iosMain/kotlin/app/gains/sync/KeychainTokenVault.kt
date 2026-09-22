package app.gains.sync

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * The bearer token as a generic password in the iOS Keychain, outside the app's database. It is
 * readable after the first unlock since boot, so a sync started in the background still works,
 * and "this device only", so it never goes into a backup or onto another phone. The Keychain
 * outlives the app, which is why [app.gains.auth.AccountRepository.forgetOrphanedToken] runs at
 * start. See docs/sync.md.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainTokenVault(private val io: CoroutineDispatcher = Dispatchers.IO) : TokenVault {

    override suspend fun get(): String? = withContext(io) {
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = withItemQuery(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) {
                SecItemCopyMatching(it, result.ptr)
            }
            when (status) {
                errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)
                    ?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }
                    ?.ifBlank { null }
                errSecItemNotFound -> null
                else -> error("Keychain read failed: $status")
            }
        }
    }

    override suspend fun set(token: String) {
        withContext(io) {
            val data = CFBridgingRetain(NSString.create(string = token).dataUsingEncoding(NSUTF8StringEncoding))
            try {
                val updated = withItemQuery { query ->
                    withDictionary(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) {
                        SecItemUpdate(query, it)
                    }
                }
                val status = if (updated != errSecItemNotFound) updated else {
                    withItemQuery(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) {
                        SecItemAdd(it, null)
                    }
                }
                check(status == errSecSuccess) { "Keychain write failed: $status" }
            } finally {
                if (data != null) CFRelease(data)
            }
        }
    }

    override suspend fun clear() {
        withContext(io) {
            val status = withItemQuery { SecItemDelete(it) }
            check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain delete failed: $status" }
        }
    }
}

/** Which Keychain item the token is: one generic password, found by service and account. */
private const val SERVICE = "app.gains.sync"
private const val ACCOUNT = "token"

/** Runs [block] with a query for the token's item plus [extra] attributes, and releases it after. */
@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withItemQuery(vararg extra: Pair<CFStringRef?, CFTypeRef?>, block: (CFDictionaryRef?) -> T): T {
    val service = CFBridgingRetain(SERVICE)
    val account = CFBridgingRetain(ACCOUNT)
    try {
        return withDictionary(kSecClass to kSecClassGenericPassword, kSecAttrService to service, kSecAttrAccount to account, *extra, block = block)
    } finally {
        CFRelease(service)
        CFRelease(account)
    }
}

/**
 * The one place Core Foundation dictionaries are built: the Security calls take a
 * `CFDictionaryRef`, whose keys are CF constants a Kotlin map can't bridge. The dictionary
 * retains what it holds and is released once [block] returns.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withDictionary(vararg entries: Pair<CFStringRef?, CFTypeRef?>, block: (CFDictionaryRef?) -> T): T {
    val dictionary = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    try {
        entries.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
        return block(dictionary)
    } finally {
        CFRelease(dictionary)
    }
}
