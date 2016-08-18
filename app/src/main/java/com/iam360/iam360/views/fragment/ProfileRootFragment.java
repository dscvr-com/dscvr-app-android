package com.iam360.iam360.views.fragment;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.iam360.iam360.R;
import com.iam360.iam360.util.Cache;

import timber.log.Timber;

public class ProfileRootFragment extends Fragment {

    private Cache cache;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		/* Inflate the layout for this fragment */
        View view = inflater.inflate(R.layout.fragment_profile_root, container, false);

        cache = Cache.getInstance(getContext());

        FragmentTransaction transaction = getFragmentManager().beginTransaction();


        if (cache.getString(Cache.USER_TOKEN).isEmpty()) {
            transaction.replace(R.id.root_frame, SigninFBFragment.newInstance("", ""));
        } else {
            Timber.d("Not logged in.");
            if (cache.getString(Cache.GATE_CODE).isEmpty()) {
                transaction.replace(R.id.root_frame, MailingListFragment.newInstance());
            } else
                transaction.replace(R.id.root_frame, ProfileFragmentExercise.newInstance(cache.getString(Cache.USER_ID)));
        }

        transaction.commit();

        return view;
    }

    public void refresh() {
        for (Fragment frag:getFragmentManager().getFragments()) {
            if (frag instanceof ProfileFragmentExercise) ((ProfileFragmentExercise) frag).refresh();
        }
    }
}