package co.optonaut.optonaut.views;

import android.databinding.DataBindingUtil;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import co.optonaut.optonaut.R;
import co.optonaut.optonaut.model.Optograph;
import co.optonaut.optonaut.network.ApiConsumer;
import co.optonaut.optonaut.util.RFC3339DateFormatter;
import co.optonaut.optonaut.viewmodels.InfiniteScrollListener;
import co.optonaut.optonaut.viewmodels.OptographAdapter;
import retrofit.Callback;
import retrofit.Response;
import retrofit.Retrofit;

/**
 * @author Nilan Marktanner
 * @date 2015-11-13
 */
public class FeedFragment extends Fragment {
    private static final String DEBUG_TAG = "Optonaut";
    private OptographAdapter adapter;
    private final int LIMIT = 5;


    public FeedFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView recList = (RecyclerView) view.findViewById(R.id.optographFeed);
        // our children have fixed size
        recList.setHasFixedSize(true);
        LinearLayoutManager llm = new LinearLayoutManager(view.getContext());
        llm.setOrientation(LinearLayoutManager.VERTICAL);
        recList.setLayoutManager(llm);
        adapter = new OptographAdapter();
        recList.setAdapter(adapter);

        recList.addOnScrollListener(new InfiniteScrollListener(llm) {
            @Override
            public void onLoadMore(int current_page) {
                loadNextOptographs();
            }
        });

        reinitializeFeed();

    }

    public void reinitializeFeed() {
        Log.v(DEBUG_TAG, "Reinitialize feed!");
        ApiConsumer apiConsumer = new ApiConsumer();
        try {
            apiConsumer.getOptographs(LIMIT, new Callback<List<Optograph>>() {
                @Override
                public void onResponse(Response<List<Optograph>> response, Retrofit retrofit) {
                    List<Optograph> optographs = response.body();
                    adapter.clear();
                    adapter.addItems(optographs);
                }

                @Override
                public void onFailure(Throwable t) {
                    Snackbar.make(getView(), "A network error occured!", Snackbar.LENGTH_SHORT).show();
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void loadNextOptographs() {
        String older_than = RFC3339DateFormatter.toRFC3339String(DateTime.now());
        if (!adapter.isEmpty()) {
            older_than = adapter.last().getCreated_at();
        }
        Log.v(DEBUG_TAG, "Load next optographs older than " + older_than);

        ApiConsumer apiConsumer = new ApiConsumer();
        try {
            apiConsumer.getOptographs(LIMIT, older_than, new Callback<List<Optograph>>() {
                @Override
                public void onResponse(Response<List<Optograph>> response, Retrofit retrofit) {
                    List<Optograph> optographs = response.body();
                    adapter.addItems(optographs);
                }

                @Override
                public void onFailure(Throwable t) {
                    Snackbar.make(getView(), "A network error occured!", Snackbar.LENGTH_SHORT).show();
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
