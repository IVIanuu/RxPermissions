/*
 * Copyright 2018 Manuel Wrage
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

package com.ivianuu.rxpermissions

import android.annotation.TargetApi
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import io.reactivex.Maybe
import io.reactivex.subjects.PublishSubject

/**
 * Handles the permission requests
 */
class RxPermissionsFragment : Fragment(), PermissionRequester, Application.ActivityLifecycleCallbacks {

    private val subjects = HashMap<Int, PublishSubject<Boolean>>()

    private val requireActivityActions = ArrayList<(() -> Unit)>()

    private var act: Activity? = null
    private var hasRegisteredCallbacks = false

    init {
        retainInstance = true
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)
        requireActivityActions.reversed().forEach { it.invoke() }
        requireActivityActions.clear()
    }

    @TargetApi(Build.VERSION_CODES.M)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!subjects.containsKey(requestCode)) return
        handlePermissionResult(requestCode, permissions, grantResults)
    }

    override fun onActivityCreated(activity: Activity?, savedInstanceState: Bundle?) {
    }

    override fun onActivityStarted(activity: Activity?) {
    }

    override fun onActivityPaused(activity: Activity?) {
    }

    override fun onActivitySaveInstanceState(activity: Activity?, outState: Bundle?) {
    }

    override fun onActivityResumed(activity: Activity?) {
    }

    override fun onActivityStopped(activity: Activity?) {
    }

    override fun onActivityDestroyed(activity: Activity?) {
        if (this.act == activity) {
            act?.application?.unregisterActivityLifecycleCallbacks(this)
            hasRegisteredCallbacks = false
            this.act = null
        }

        activePermissionFragments.remove(activity)
    }

    override fun request(vararg permissions: String): Maybe<Boolean> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return Maybe.just(true)
        }

        if (activity != null) {
            val granted = permissions
                .all { activity!!.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                return Maybe.just(true)
            }
        }

        val requestCode = RequestCodeGenerator.generate()

        val subject = PublishSubject.create<Boolean>()
        subjects[requestCode] = subject

        requireActivity {
            val granted = permissions
                .all { activity!!.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                subjects.remove(requestCode)
                subject.onNext(granted)
                subject.onComplete()
            } else {
                requestPermissions(permissions, requestCode)
            }
        }

        return subject
            .take(1)
            .singleElement()
    }

    private fun handlePermissionResult(requestCode: Int,
                                       permissions: Array<out String>,
                                       grantResults: IntArray) {
        val subject = subjects.remove(requestCode) ?: return

        val granted = (0 until permissions.size)
            .map(grantResults::get)
            .all { it == PackageManager.PERMISSION_GRANTED }

        subject.onNext(granted)
        subject.onComplete()
    }

    private fun requireActivity(action: () -> Unit) {
        if (activity != null) {
            action()
        } else {
            requireActivityActions.add(action)
        }
    }

    private fun registerActivityListener(activity: Activity) {
        this.act = activity

        if (!hasRegisteredCallbacks) {
            hasRegisteredCallbacks = true
            activity.application.registerActivityLifecycleCallbacks(this)
            activePermissionFragments[activity] = this
        }
    }

    companion object {
        private const val TAG_FRAGMENT = "com.ivianuu.rxpermissions.RxPermissionsFragment"

        private val activePermissionFragments = HashMap<Activity, RxPermissionsFragment>()

        internal fun get(activity: FragmentActivity): PermissionRequester {
            var permissionsFragment = findInActivity(activity)
            if (permissionsFragment == null) {
                permissionsFragment = RxPermissionsFragment()
                activity.supportFragmentManager.beginTransaction()
                    .add(permissionsFragment, TAG_FRAGMENT)
                    .commit()
            }

            permissionsFragment.registerActivityListener(activity)

            return permissionsFragment
        }

        private fun findInActivity(activity: FragmentActivity): RxPermissionsFragment? {
            var permissionsFragment = activePermissionFragments[activity]
            if (permissionsFragment == null) {
                permissionsFragment = activity.supportFragmentManager
                    .findFragmentByTag(TAG_FRAGMENT) as RxPermissionsFragment?
            }

            return permissionsFragment
        }
    }
}