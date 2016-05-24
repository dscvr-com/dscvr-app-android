package com.iam360.iam360.views.new_design;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.view.WindowManager;
import android.util.Log;

import com.facebook.FacebookSdk;
import com.facebook.appevents.AppEventsLogger;

import java.util.UUID;

import com.iam360.iam360.R;
import com.iam360.iam360.model.Optograph;
import com.iam360.iam360.sensors.CoreMotionListener;
import com.iam360.iam360.util.Cache;
import com.iam360.iam360.util.Constants;
import com.iam360.iam360.views.GestureDetectors;
import com.iam360.iam360.views.SettingsActivity;
import com.iam360.iam360.views.profile.SigninFBFragment;

public class MainActivity extends AppCompatActivity {
    public static final int SHARING_MODE = 0;
    public static final int FEED_MODE = 1;
    public static final int PROFILE_MODE = 2;
    private MyPagerAdapter adapterViewPager;
    private ViewPager viewPager;
    private Cache cache;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        initializeComponents();

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);

        viewPager = (ViewPager) findViewById(R.id.vpPager);
        adapterViewPager = new MyPagerAdapter(getSupportFragmentManager());
        viewPager.setAdapter(adapterViewPager);
        viewPager.setCurrentItem(FEED_MODE, false);

        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

            }

            @Override
            public void onPageSelected(int position) {
                Log.d("myTag"," profile? "+(position==PROFILE_MODE)+" sharing? "+(position==SHARING_MODE));
                if(position == SHARING_MODE) {
                    adapterViewPager.getSharingFragment().updateOptograph();
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {

            }
        });

//        new GeneralUtils().setStatusBarTranslucent(this, true);
//        setStatusBarTranslucent(true);
//        int statusBarHeight = new GeneralUtils().getStatusBarHeight(this);
    }

    protected void setStatusBarTranslucent(boolean makeTranslucent) {
        if (makeTranslucent) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        }
    }

    public void setPage(int page) {
        viewPager.setCurrentItem(page, true);
    }


    public void dragSharePage () {
        viewPager.setCurrentItem(SHARING_MODE,true);

    }



    public void startSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    public void startImagePreview(UUID id, String imagePath) {
        Intent intent = new Intent(this, OptoImagePreviewActivity.class);
        intent.putExtra("id", id.toString());
        if(imagePath != null) intent.putExtra("path", imagePath);
        startActivity(intent);
    }

    public void setOptograph(Optograph optograph) {
        adapterViewPager.updateShare(optograph);
//        this.optograph = optograph;
    }

    private void initializeComponents() {

        // instatiate cache on start of application
        cache = Cache.getInstance(this);

        Constants.initializeConstants(this);
        GestureDetectors.initialize(this);
        CoreMotionListener.initialize(this);

        // FB Track App Installs and App Opens
        FacebookSdk.sdkInitialize(getApplicationContext());
        AppEventsLogger.activateApp(this);

    }

    public class MyPagerAdapter extends FragmentPagerAdapter {
        private int NUM_ITEMS = 3;
        private Cache cache;
        private SharingFragment sharingFragment;

        public MyPagerAdapter(FragmentManager fragmentManager) {
            super(fragmentManager);
            cache = Cache.open();
        }

        // Returns total number of pages
        @Override
        public int getCount() {
            return NUM_ITEMS;
        }

        // Returns the fragment to display for that page
        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    sharingFragment = new SharingFragment();
                    return sharingFragment;
//                    return SharingFragment.newInstance();
                case 1:
                    return new MainFeedFragment();
                case 2:
                    return new ProfileRootFragment();
//                    if (cache.getString(Cache.USER_TOKEN).isEmpty()) {
//                        return SigninFBFragment.newInstance("","");
//                    } else
//                        return ProfileFragment.newInstance(cache.getString(Cache.USER_ID));
//                    return ProfileFragment.newInstance("c0d5cb2b-7f8a-4de9-a5de-6f7c6cf1cf1a");

                default:
                    return null;
            }
        }

        @Override
        public int getItemPosition(Object object) {
            if(object instanceof  SharingFragment) {
            } else if (object instanceof SigninFBFragment) {
                return POSITION_NONE;
            }
            return POSITION_UNCHANGED;
//            return super.getItemPosition(object);
        }

        public void updateShare(Optograph optograph) {
            sharingFragment.setOptograph(optograph);
        }

        public SharingFragment getSharingFragment() {
            return sharingFragment;
        }

        // Returns the page title for the top indicator
        @Override
        public CharSequence getPageTitle(int position) {
            return "Page " + position;
        }

    }

    @Override
    public void onBackPressed() {
        Log.d("myTag","MainActivity onBackPressed. Profile?"+(viewPager.getCurrentItem()==PROFILE_MODE));
        if (viewPager.getCurrentItem() != FEED_MODE) {
            setPage(FEED_MODE);
        } else super.onBackPressed();
    }

    public void onBack() {
        // TODO: how to update the page of profile after login
        onBackPressed();
    }
}