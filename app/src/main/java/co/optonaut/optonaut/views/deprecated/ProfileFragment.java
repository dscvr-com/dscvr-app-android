package co.optonaut.optonaut.views.deprecated;

import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.squareup.otto.Subscribe;

import co.optonaut.optonaut.BR;
import co.optonaut.optonaut.ProfileBinding;
import co.optonaut.optonaut.R;
import co.optonaut.optonaut.bus.BusProvider;
import co.optonaut.optonaut.bus.PersonReceivedEvent;
import co.optonaut.optonaut.model.Person;
import co.optonaut.optonaut.network.PersonManager;

/**
 * @author Nilan Marktanner
 * @date 2015-12-04
 */
public class ProfileFragment extends Fragment {
    private static final String DEBUG_TAG = "Optonaut";
    private static final String PROFILE_FEED_FRAGMENT_TAG = "PROFILE_FEED_FRAGMENT_TAG";
    Person person;
    ProfileBinding binding;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args.containsKey("person")) {
            person = args.getParcelable("person");
        } else if (args.containsKey("id")) {
            PersonManager.loadPerson(args.getString("id"));
        } else {
            throw new RuntimeException();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.profile_fragment, container, false);
        binding.setVariable(BR.person, person);
        binding.executePendingBindings();

        if (person != null) {
            initializeProfileFeed();
        }

        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        BusProvider.getInstance().register(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        BusProvider.getInstance().unregister(this);
    }

    @Subscribe
    public void reveicePerson(PersonReceivedEvent personReceivedEvent) {
        Log.d("Optonaut", "Registered person");
        person = personReceivedEvent.getPerson();
        binding.setVariable(BR.person, person);


        initializeProfileFeed();

        binding.executePendingBindings();
    }

    private void initializeProfileFeed() {
        ProfileFeedFragment profileFeedFragment = ProfileFeedFragment.newInstance(person);

        getChildFragmentManager().beginTransaction().
                replace(R.id.feed_placeholder, profileFeedFragment).addToBackStack(null).commit();
    }

    public static ProfileFragment newInstance(Person person) {
        ProfileFragment profileFragment = new ProfileFragment();
        Bundle args = new Bundle();
        args.putParcelable("person", person);
        profileFragment.setArguments(args);
        return profileFragment;
    }



}