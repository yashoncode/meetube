package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.os.Bundle;

import com.liskovsoft.smartyoutubetv2.common.R;

/**
 * MEETUBE: About > Donation. The international (Buy me a coffee) and India (UPI) QR codes side by side; Back closes it.
 */
public class DonateActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_donate);
    }
}
