package co.optonaut.optonaut.views;

import android.content.Context;
import android.databinding.DataBindingUtil;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import co.optonaut.optonaut.BR;
import co.optonaut.optonaut.Optograph2DBinding;
import co.optonaut.optonaut.R;
import co.optonaut.optonaut.model.Optograph;
import co.optonaut.optonaut.opengl.Optograph2DView;

/**
 * @author Nilan Marktanner
 * @date 2015-12-02
 */
public class Optograph2DFragment extends Fragment {
    private static final String DEBUG_TAG = "Optonaut";
    private Optograph2DBinding binding;
    private Optograph optograph;

    private Optograph2DView optograph2DView;
    private SensorManager sensorManager;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        optograph = args.getParcelable("optograph");
        sensorManager = (SensorManager) getActivity().getSystemService(Context.SENSOR_SERVICE);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.optograph_2d_view, container, false);
        binding.setVariable(BR.optograph, optograph);
        binding.setVariable(BR.person, optograph.getPerson());
        binding.executePendingBindings();

        final View view = binding.getRoot();

        ImageView profileView = (ImageView) view.findViewById(R.id.person_avatar_asset);
        profileView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ((MainActivity) view.getContext()).openProfileFragment(binding.getPerson());
            }
        });

        this.optograph2DView = (Optograph2DView) view.findViewById(R.id.GLSurface);
        //registerRotationVectorListener();

        return view;
    }

    public static Optograph2DFragment newInstance(Optograph optograph) {
        Optograph2DFragment optograph2DFragment = new Optograph2DFragment();
        Bundle args = new Bundle();
        args.putParcelable("optograph", optograph);
        optograph2DFragment.setArguments(args);
        return optograph2DFragment;
    }

    @Override
    public void onResume() {
        super.onResume();
        this.optograph2DView.onResume();
        //registerRotationVectorListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        this.optograph2DView.onPause();
        //unregisterRotationVectorListener();
    }

    private void registerRotationVectorListener() {
        sensorManager.registerListener(optograph2DView.getOptographRenderer(), sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void unregisterRotationVectorListener() {
        sensorManager.unregisterListener(optograph2DView.getOptographRenderer());
    }
}
