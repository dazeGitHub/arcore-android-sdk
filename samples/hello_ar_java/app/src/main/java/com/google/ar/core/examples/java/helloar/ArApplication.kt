package com.google.ar.core.examples.java.helloar

import android.app.Application
import android.content.Context
import com.hjq.toast.ToastUtils

/**
 * <pre>
 *     author : ZYZ
 *     e-mail : zyz163mail@163.com
 *     time   : 2020/12/23
 *     desc   :
 *     version: 1.0
 * </pre>
 */

 class ArApplication : Application(){

    override fun onCreate() {
        super.onCreate()
        ToastUtils.init(this)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
//        MultiDex.install(this)
    }

}