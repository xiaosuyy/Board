package com.board.ime.ime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/**
 * 只做一件事：**弹出系统麦克风授权弹窗**。
 *
 * 输入法是 Service，自己不能用 `requestPermissions()`（那是 Activity 的能力），
 * 所以用一个没有任何界面的透明 Activity 代劳；申请完立即结束。
 *
 * 被拒绝（含「不再询问」）时退回到「应用详情页」，让用户仍能手动打开麦克风权限，
 * 避免卡在「点了也没反应」。
 */
class MicPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            finish()
            return
        }
        runCatching {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
        }.onFailure {
            openAppDetails()
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) openAppDetails()
        finish()
    }

    private fun openAppDetails() {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null)),
            )
        }
    }

    private companion object {
        const val REQUEST_CODE = 1001
    }
}
