/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.net;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.util.CollectionUtils;
import com.android.server.net.NetworkStatsSubscriptionsMonitor.Delegate;
import com.android.server.net.NetworkStatsSubscriptionsMonitor.RatTypeListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@RunWith(JUnit4.class)
public final class NetworkStatsSubscriptionsMonitorTest {
    private static final int TEST_SUBID1 = 3;
    private static final int TEST_SUBID2 = 5;
    private static final String TEST_IMSI1 = "466921234567890";
    private static final String TEST_IMSI2 = "466920987654321";
    private static final String TEST_IMSI3 = "466929999999999";

    @Mock private Context mContext;
    @Mock private PhoneStateListener mPhoneStateListener;
    @Mock private SubscriptionManager mSubscriptionManager;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private Delegate mDelegate;
    private final List<Integer> mTestSubList = new ArrayList<>();

    private final Executor mExecutor = Executors.newSingleThreadExecutor();
    private NetworkStatsSubscriptionsMonitor mMonitor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        when(mTelephonyManager.createForSubscriptionId(anyInt())).thenReturn(mTelephonyManager);

        when(mContext.getSystemService(eq(Context.TELEPHONY_SUBSCRIPTION_SERVICE)))
                .thenReturn(mSubscriptionManager);
        when(mContext.getSystemService(eq(Context.TELEPHONY_SERVICE)))
                .thenReturn(mTelephonyManager);

        mMonitor = new NetworkStatsSubscriptionsMonitor(mContext, mExecutor, mDelegate);
    }

    @Test
    public void testStartStop() {
        // Verify that addOnSubscriptionsChangedListener() is never called before start().
        verify(mSubscriptionManager, never())
                .addOnSubscriptionsChangedListener(mExecutor, mMonitor);
        mMonitor.start();
        verify(mSubscriptionManager).addOnSubscriptionsChangedListener(mExecutor, mMonitor);

        // Verify that removeOnSubscriptionsChangedListener() is never called before stop()
        verify(mSubscriptionManager, never()).removeOnSubscriptionsChangedListener(mMonitor);
        mMonitor.stop();
        verify(mSubscriptionManager).removeOnSubscriptionsChangedListener(mMonitor);
    }

    @NonNull
    private static int[] convertArrayListToIntArray(@NonNull List<Integer> arrayList) {
        final int[] list = new int[arrayList.size()];
        for (int i = 0; i < arrayList.size(); i++) {
            list[i] = arrayList.get(i);
        }
        return list;
    }

    private void setRatTypeForSub(List<RatTypeListener> listeners,
            int subId, int type) {
        final ServiceState serviceState = mock(ServiceState.class);
        when(serviceState.getDataNetworkType()).thenReturn(type);
        final RatTypeListener match = CollectionUtils
                .find(listeners, it -> it.getSubId() == subId);
        if (match != null) {
            match.onServiceStateChanged(serviceState);
        }
    }

    private void addTestSub(int subId, String subscriberId) {
        // add SubId to TestSubList.
        if (!mTestSubList.contains(subId)) {
            mTestSubList.add(subId);
        }
        final int[] subList = convertArrayListToIntArray(mTestSubList);
        when(mSubscriptionManager.getCompleteActiveSubscriptionIdList()).thenReturn(subList);
        when(mTelephonyManager.getSubscriberId(subId)).thenReturn(subscriberId);
        mMonitor.onSubscriptionsChanged();
    }

    private void removeTestSub(int subId) {
        // Remove subId from TestSubList.
        mTestSubList.removeIf(it -> it == subId);
        final int[] subList = convertArrayListToIntArray(mTestSubList);
        when(mSubscriptionManager.getCompleteActiveSubscriptionIdList()).thenReturn(subList);
        mMonitor.onSubscriptionsChanged();
    }

    private void assertRatTypeChangedForSub(String subscriberId, int ratType) {
        assertEquals(mMonitor.getRatTypeForSubscriberId(subscriberId), ratType);
        final ArgumentCaptor<Integer> typeCaptor = ArgumentCaptor.forClass(Integer.class);
        // Verify callback with the subscriberId and the RAT type should be as expected.
        // It will fail if get a callback with an unexpected RAT type.
        verify(mDelegate).onCollapsedRatTypeChanged(eq(subscriberId), typeCaptor.capture());
        final int type = typeCaptor.getValue();
        assertEquals(ratType, type);
    }

    private void assertRatTypeNotChangedForSub(String subscriberId, int ratType) {
        assertEquals(mMonitor.getRatTypeForSubscriberId(subscriberId), ratType);
        // Should never get callback with any RAT type.
        verify(mDelegate, never()).onCollapsedRatTypeChanged(eq(subscriberId), anyInt());
    }

    @Test
    public void testSubChangedAndRatTypeChanged() {
        final ArgumentCaptor<RatTypeListener> ratTypeListenerCaptor =
                ArgumentCaptor.forClass(RatTypeListener.class);

        mMonitor.start();
        // Insert sim1, verify RAT type is NETWORK_TYPE_UNKNOWN, and never get any callback
        // before changing RAT type.
        addTestSub(TEST_SUBID1, TEST_IMSI1);
        assertRatTypeNotChangedForSub(TEST_IMSI1, TelephonyManager.NETWORK_TYPE_UNKNOWN);

        // Insert sim2.
        addTestSub(TEST_SUBID2, TEST_IMSI2);
        assertRatTypeNotChangedForSub(TEST_IMSI1, TelephonyManager.NETWORK_TYPE_UNKNOWN);
        verify(mTelephonyManager, times(2)).listen(ratTypeListenerCaptor.capture(),
                eq(PhoneStateListener.LISTEN_SERVICE_STATE));
        reset(mDelegate);

        // Set RAT type of sim1 to UMTS.
        // Verify RAT type of sim1 after subscription gets onCollapsedRatTypeChanged() callback
        // and others remain untouched.
        setRatTypeForSub(ratTypeListenerCaptor.getAllValues(), TEST_SUBID1,
                TelephonyManager.NETWORK_TYPE_UMTS);
        assertRatTypeChangedForSub(TEST_IMSI1, TelephonyManager.NETWORK_TYPE_UMTS);
        assertRatTypeNotChangedForSub(TEST_IMSI2, TelephonyManager.NETWORK_TYPE_UNKNOWN);
        assertRatTypeNotChangedForSub(TEST_IMSI3, TelephonyManager.NETWORK_TYPE_UNKNOWN);
        reset(mDelegate);

        // Set RAT type of sim2 to LTE.
        // Verify RAT type of sim2 after subscription gets onCollapsedRatTypeChanged() callback
        // and others remain untouched.
        setRatTypeForSub(ratTypeListenerCaptor.getAllValues(), TEST_SUBID2,
                TelephonyManager.NETWORK_TYPE_LTE);
        assertRatTypeNotChangedForSub(TEST_IMSI1, TelephonyManager.NETWORK_TYPE_UMTS);
        assertRatTypeChangedForSub(TEST_IMSI2, TelephonyManager.NETWORK_TYPE_LTE);
        assertRatTypeNotChangedForSub(TEST_IMSI3, TelephonyManager.NETWORK_TYPE_UNKNOWN);
        reset(mDelegate);

        // Remove sim2 and verify that callbacks are fired and RAT type is correct for sim2.
        // while the other two remain untouched.
        removeTestSub(TEST_SUBID2);
        verify(mTelephonyManager).listen(any(), eq(PhoneStateListener.LISTEN_NONE));
        assertRatTypeNotChangedForSub(TEST_IMSI1, TelephonyManager.NETWORK_TYPE_UMTS);
        assertRatTypeChangedForSub(TEST_IMSI2, TelephonyManager.NETWORK_TYPE_UNKNOWN);
        assertRatTypeNotChangedForSub(TEST_IMSI3, TelephonyManager.NETWORK_TYPE_UNKNOWN);
        reset(mDelegate);

        // Set RAT type of sim1 to UNKNOWN. Then stop monitoring subscription changes
        // and verify that the listener for sim1 is removed.
        setRatTypeForSub(ratTypeListenerCaptor.getAllValues(), TEST_SUBID1,
                TelephonyManager.NETWORK_TYPE_UNKNOWN);
        assertRatTypeChangedForSub(TEST_IMSI1, TelephonyManager.NETWORK_TYPE_UNKNOWN);
        reset(mDelegate);

        mMonitor.stop();
        verify(mTelephonyManager, times(2)).listen(any(), eq(PhoneStateListener.LISTEN_NONE));
        assertRatTypeChangedForSub(TEST_IMSI1, TelephonyManager.NETWORK_TYPE_UNKNOWN);
    }
}
