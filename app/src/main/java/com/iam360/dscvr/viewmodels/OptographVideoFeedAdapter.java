package com.iam360.dscvr.viewmodels;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.databinding.DataBindingUtil;
import android.support.design.widget.Snackbar;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.daimajia.swipe.SwipeLayout;
import com.iam360.dscvr.BR;
import com.iam360.dscvr.NewFeedItemBinding;
import com.iam360.dscvr.R;
import com.iam360.dscvr.model.Location;
import com.iam360.dscvr.model.LogInReturn;
import com.iam360.dscvr.model.Optograph;
import com.iam360.dscvr.model.Person;
import com.iam360.dscvr.network.ApiConsumer;
import com.iam360.dscvr.util.Cache;
import com.iam360.dscvr.util.Constants;
import com.iam360.dscvr.util.DBHelper;
import com.iam360.dscvr.util.NotificationSender;
import com.iam360.dscvr.views.activity.MainActivity;
import com.iam360.dscvr.views.activity.OptographDetailsActivity;
import com.iam360.dscvr.views.activity.ProfileActivity;

import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.List;

import retrofit.Callback;
import retrofit.Response;
import retrofit.Retrofit;
import timber.log.Timber;

public class OptographVideoFeedAdapter extends RecyclerView.Adapter<OptographVideoFeedAdapter.OptographHolder> {
    private static final int ITEM_HEIGHT = Constants.getInstance().getDisplayMetrics().heightPixels;
    private static final float ITEM_WIDTH = Constants.getInstance().getDisplayMetrics().widthPixels;
    private static final float DENSITY = Constants.getInstance().getDisplayMetrics().density;
    private List<Optograph> optographs;

    protected ApiConsumer apiConsumer;
    private Cache cache;
    private Context context;
    private DBHelper mydb;

    private boolean isCurrentUser = false;
    private boolean draggingPage = false;

    public OptographVideoFeedAdapter(Context context) {
        this.context = context;
        this.optographs = new ArrayList<>();

        cache = Cache.open();

        String token = cache.getString(Cache.USER_TOKEN);
        apiConsumer = new ApiConsumer(token.equals("") ? null : token);
        mydb = new DBHelper(context);
    }

    @Override
    public OptographHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.new_feed_item, parent, false);
        return new OptographHolder(view, context);
    }

    @Override
    public void onAttachedToRecyclerView(RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
    }

    @Override
    public void onBindViewHolder(OptographHolder holder, int position, List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
        } else {
            holder.getBinding().swipeLayout.close(true);
        }
    }

    @Override
    public void onBindViewHolder(OptographHolder holder, int position) {
//        super.onBindViewHolder(holder, position);
        Optograph optograph = optographs.get(position);

//        if (!optograph.equals(holder.getBinding().getOptograph())) {
            int width = (int) ITEM_WIDTH;
            int height = (int)((ITEM_WIDTH / 1.405) + (5 * DENSITY));
//            int height = (int)(((ITEM_WIDTH / 3) * 2) + (5 * DENSITY));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
            holder.itemView.setLayoutParams(params);

            holder.getBinding().frame.setOnClickListener(v -> callDetailsPage(optograph, position));
            holder.getBinding().videoView.setOnClickListener(v -> callDetailsPage(optograph, position));
            holder.getBinding().previewImage.setOnClickListener(v -> callDetailsPage(optograph, position));

            holder.getBinding().heartLabel.setTypeface(Constants.getInstance().getIconTypeface());
            holder.getBinding().heartLabel.setOnClickListener(v -> setHeart(optograph, holder, v));
            holder.getBinding().heartContainer.setOnClickListener(v -> { setHeart(optograph, holder, v); });

            isCurrentUser = optograph.getPerson().getId().equals(cache.getString(Cache.USER_ID));
            holder.getBinding().follow.setVisibility(isCurrentUser ? View.GONE : View.VISIBLE);
            holder.getBinding().follow.setOnClickListener(v -> followOrUnfollow(optograph, holder, v));
            holder.getBinding().followContainer.setVisibility(isCurrentUser ? View.GONE : View.VISIBLE);
            holder.getBinding().followContainer.setOnClickListener(v -> followOrUnfollow(optograph, holder, v));

            holder.getBinding().personLocationInformation.setOnClickListener(v -> startProfile(optograph.getPerson()));
            holder.getBinding().personAvatarAsset.setOnClickListener(v -> startProfile(optograph.getPerson()));
//        holder.getBinding().dateAndId.setText(optograph.getId()+"\n"+ RFC3339DateFormatter.toRFC3339String(optograph.getCreated_atDateTime())+"\n"+optograph.getText());

            updateHeartLabel(optograph, holder);
            followPerson(optograph, optograph.getPerson().is_followed(), holder, true);

            // setup sharing
            TextView settingsLabel = (TextView) holder.itemView.findViewById(R.id.settings_button);
            settingsLabel.setOnClickListener(v -> Snackbar.make(v, v.getResources().getString(R.string.feature_soon), Snackbar.LENGTH_SHORT).show());

            SwipeLayout swipeLayout = (SwipeLayout) holder.itemView.findViewById(R.id.swipe_layout);
            swipeLayout.setShowMode(SwipeLayout.ShowMode.PullOut);
            swipeLayout.setBottomSwipeEnabled(false);
            swipeLayout.setTopSwipeEnabled(false);
            swipeLayout.setRightSwipeEnabled(false);

            View shareButton = swipeLayout.findViewById(R.id.bottom_wrapper);
//            swipeLayout.addDrag(SwipeLayout.DragEdge.Right, shareButton);

            LinearLayout barSwipe = (LinearLayout) holder.itemView.findViewById(R.id.bar_swipe);

            swipeLayout.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    ViewParent parent = v.getParent();

                    if(event.getAction() == MotionEvent.ACTION_UP) {
                        swipeLayout.close(true);
                    }
                    if(draggingPage) {
                        parent.requestDisallowInterceptTouchEvent(false);
                        return true;
                    } else {
                        parent.requestDisallowInterceptTouchEvent(true);
                        return false;
                    }

                }
            });

            swipeLayout.addSwipeListener(new SwipeLayout.SwipeListener() {
                @Override
                public void onStartOpen(SwipeLayout layout) {
//                    barSwipe.setVisibility(View.GONE);
                    //((MainActivity) context).setOptograph(optograph);
                }

                @Override
                public void onOpen(SwipeLayout layout) {
                    ((MainActivity) context).setOptograph(optograph);
                    draggingPage = true;
                }

                @Override
                public void onStartClose(SwipeLayout layout) {}

                @Override
                public void onClose(SwipeLayout layout) {
                    barSwipe.setVisibility(View.VISIBLE);
                }

                @Override
                public void onUpdate(SwipeLayout layout, int leftOffset, int topOffset) {
                }

                @Override
                public void onHandRelease(SwipeLayout layout, float xvel, float yvel) {}
            });

            barSwipe.setOnClickListener(v -> {
                swipeLayout.bounce(300, shareButton);
                ((MainActivity) context).setOptograph(optograph);
            });

            holder.getBinding().setVariable(BR.optograph, optograph);
            holder.getBinding().setVariable(BR.person, optograph.getPerson());
            holder.getBinding().setVariable(BR.location, optograph.getLocation());


            holder.getBinding().executePendingBindings();
//        } else {
//        }

    }

//    @Nullable
//    @Override
//    protected Object getItem(int position) {
//        return optographs.get(position);
//    }

    private void startProfile(Person person) {
        if(cache.getString(Cache.USER_ID).equals(person)) {
            if(context instanceof MainActivity)
                ((MainActivity) context).setPage(MainActivity.PROFILE_MODE);
        } else {
            Intent intent = new Intent(context, ProfileActivity.class);
            intent.putExtra("person", person);
            context.startActivity(intent);
        }
    }

    private void callDetailsPage(Optograph optograph, int position) {

        if(true) {
            Intent intent = new Intent(context, OptographDetailsActivity.class);
            intent.putParcelableArrayListExtra("opto_list", getNextOptographList(position, 5));
            if(context instanceof MainActivity)
                ((MainActivity) context).startActivityForResult(intent, OptographLocalGridAdapter.DELETE_IMAGE);
        } else {
            Intent intent = new Intent(context, OptographDetailsActivity.class);
            intent.putExtra("opto", optograph);
            context.startActivity(intent);
        }
    }

    private ArrayList<Optograph> getNextOptographList(int position, int count) {
        int optoListCount = optographs.size();
        count = (count < optoListCount) ? count : optoListCount;

        ArrayList<Optograph> optographList = new ArrayList<Optograph>();

        for(int i = 0; i < count; i++) {
            optographList.add(optographs.get((position) % optoListCount));
            position++;
        }

        return optographList;
    }

    private void followOrUnfollow(Optograph optograph, OptographHolder holder, View v) {

        if (!cache.getString(Cache.USER_TOKEN).equals("")) {
            if (optograph.getPerson().is_followed()) {
                followPerson(optograph, false, holder);
                apiConsumer.unfollow(optograph.getPerson().getId(), new Callback<LogInReturn.EmptyResponse>() {
                    @Override
                    public void onResponse(Response<LogInReturn.EmptyResponse> response, Retrofit retrofit) {
                        // revert follow count on failure
                        if (!response.isSuccess()) {
                            followPerson(optograph, true, holder);
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        followPerson(optograph, true, holder);
                        Timber.e("Error on unfollowing.");
                    }
                });
            } else if (!optograph.getPerson().is_followed()) {
                followPerson(optograph, true, holder);
                apiConsumer.follow(optograph.getPerson().getId(), new Callback<LogInReturn.EmptyResponse>() {
                    @Override
                    public void onResponse(Response<LogInReturn.EmptyResponse> response, Retrofit retrofit) {
                        // revert follow count on failure
                        if (!response.isSuccess()) {
                            followPerson(optograph, false, holder);
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        followPerson(optograph, false, holder);
                        Timber.e("Error on following.");
                    }
                });
            }
        } else {
            Snackbar.make(v,context.getString(R.string.profile_login_first),Snackbar.LENGTH_SHORT).show();
        }
    }

    private void setHeart(Optograph optograph, OptographHolder holder, View v) {
        if(!cache.getString(Cache.USER_TOKEN).equals("")) {
            if (!optograph.is_starred()) {
                mydb.updateColumnOptograph(optograph.getId(), DBHelper.OPTOGRAPH_IS_STARRED, true);
                optograph.setIs_starred(true);
                optograph.setStars_count(optograph.getStars_count() + 1);
                updateHeartLabel(optograph, holder);
                apiConsumer.postStar(optograph.getId(), new Callback<LogInReturn.EmptyResponse>() {
                    @Override
                    public void onResponse(Response<LogInReturn.EmptyResponse> response, Retrofit retrofit) {
                        if (!response.isSuccess()) {
                            mydb.updateColumnOptograph(optograph.getId(), DBHelper.OPTOGRAPH_IS_STARRED, false);
                            optograph.setIs_starred(response.isSuccess());
                            optograph.setStars_count(optograph.getStars_count() - 1);
                            updateHeartLabel(optograph, holder);
                        } else {
                            Cursor res = mydb.getData(optograph.getId(), DBHelper.OPTO_TABLE_NAME_FEEDS, DBHelper.OPTOGRAPH_ID);
                            res.moveToFirst();
                            if (res.getCount() > 0) {
                                mydb.updateTableColumn(DBHelper.OPTO_TABLE_NAME_FEEDS, DBHelper.OPTOGRAPH_ID, optograph.getId(), "optograph_is_starred", true);
                                mydb.updateTableColumn(DBHelper.OPTO_TABLE_NAME_FEEDS, DBHelper.OPTOGRAPH_ID, optograph.getId(), "optograph_stars_count", optograph.getStars_count());
                            }
                            res.close();
                            if(!optograph.getId().equals(cache.getString(Cache.USER_ID)))
                                NotificationSender.triggerSendNotification(optograph, "like", optograph.getId());
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        mydb.updateColumnOptograph(optograph.getId(), DBHelper.OPTOGRAPH_IS_STARRED, false);
                        optograph.setIs_starred(false);
                        optograph.setStars_count(optograph.getStars_count() - 1);
                        updateHeartLabel(optograph, holder);
                    }
                });
            } else if (optograph.is_starred()) {
                mydb.updateColumnOptograph(optograph.getId(), DBHelper.OPTOGRAPH_IS_STARRED, false);
                optograph.setIs_starred(false);
                optograph.setStars_count(optograph.getStars_count() - 1);
                updateHeartLabel(optograph, holder);
                apiConsumer.deleteStar(optograph.getId(), new Callback<LogInReturn.EmptyResponse>() {
                    @Override
                    public void onResponse(Response<LogInReturn.EmptyResponse> response, Retrofit retrofit) {
                        if (!response.isSuccess()) {
                            mydb.updateColumnOptograph(optograph.getId(), DBHelper.OPTOGRAPH_IS_STARRED, true);
                            optograph.setIs_starred(response.isSuccess());
                            optograph.setStars_count(optograph.getStars_count() + 1);
                            updateHeartLabel(optograph, holder);
                        } else {
                            Cursor res = mydb.getData(optograph.getId(), DBHelper.OPTO_TABLE_NAME_FEEDS, DBHelper.OPTOGRAPH_ID);
                            res.moveToFirst();
                            if (res.getCount() > 0) {
                                mydb.updateTableColumn(DBHelper.OPTO_TABLE_NAME_FEEDS, DBHelper.OPTOGRAPH_ID, optograph.getId(), "optograph_is_starred", false);
                                mydb.updateTableColumn(DBHelper.OPTO_TABLE_NAME_FEEDS, DBHelper.OPTOGRAPH_ID, optograph.getId(), "optograph_stars_count", optograph.getStars_count());
                            }
                            res.close();
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        mydb.updateColumnOptograph(optograph.getId(), DBHelper.OPTOGRAPH_IS_STARRED, true);
                        optograph.setIs_starred(true);
                        optograph.setStars_count(optograph.getStars_count() + 1);
                        updateHeartLabel(optograph, holder);
                    }
                });
            }
        } else {
            Snackbar.make(v, context.getString(R.string.profile_login_first), Snackbar.LENGTH_SHORT).show();
        }
    }

    private void updateHeartLabel(Optograph optograph, OptographHolder holder) {
//        holder.getBinding().heartLabel.setText(String.valueOf(optograph.getStars_count()));
        holder.getBinding().heartLabel.setText("");
        if(optograph.is_starred()) {
            holder.getBinding().heartLabel.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.liked_icn, 0);
        } else {
            holder.getBinding().heartLabel.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.like_icn, 0);
        }
    }

    private void followPerson(Optograph optograph, boolean isFollowed, OptographHolder holder) {
        followPerson(optograph, isFollowed, holder, false);
    }

    /**
     *
     * @param optograph
     * @param isFollowed
     * @param holder
     * @param isInitial initial binding of opto list, if for onclick use false
     */
    private void followPerson(Optograph optograph, boolean isFollowed, OptographHolder holder, boolean isInitial) {
        Cursor res = mydb.getData(optograph.getPerson().getId(), DBHelper.PERSON_TABLE_NAME, "id");
        res.moveToFirst();

        if(isInitial) {
            // if for initial binding, just set properties, no need for notification
            if(isFollowed) {
                optograph.getPerson().setIs_followed(true);
                optograph.getPerson().setFollowers_count(optograph.getPerson().getFollowers_count() + 1);
                holder.getBinding().follow.setImageResource(R.drawable.feed_following_icn);
            } else {
                optograph.getPerson().setIs_followed(false);
                optograph.getPerson().setFollowers_count(optograph.getPerson().getFollowers_count() - 1);
                holder.getBinding().follow.setImageResource(R.drawable.feed_follow_icn);
            }

        } else {
            // for onclick case, trigger notification if followed
            if(isFollowed)
                NotificationSender.triggerSendNotification(optograph.getPerson(), "follow");

            // refresh all items on list with the same person
            refreshAllOptoThatContainsPersonId(optograph.getPerson().getId(), isFollowed, holder);
        }

        if (res.getCount() > 0) {
            mydb.updateTableColumn(DBHelper.PERSON_TABLE_NAME,"id", optograph.getPerson().getId(), "is_followed", optograph.getPerson().is_followed());
            mydb.updateTableColumn(DBHelper.PERSON_TABLE_NAME, "id", optograph.getPerson().getId(), "followers_count", optograph.getPerson().getFollowers_count());
        }

        res.close();

    }

    @Override
    public int getItemCount() {
        return optographs.size();
    }

    public void addItem(Optograph optograph) {
        if (optograph == null) {
            return;
        }

        saveToSQLiteFeeds(optograph);
        DateTime created_at = optograph.getCreated_atDateTime();

        // skip if optograph is already in list
        if (optographs.contains(optograph)) {
            int i = optographs.indexOf(optograph);
            optographs.set(i, optograph);
            notifyItemChanged(optographs.indexOf(optograph));
            return;
        }

        // if list is empty, simply add new optograph
        if (optographs.isEmpty()) {
            optographs.add(optographs.size(), optograph);
//            notifyItemInserted(getItemCount());p
            notifyItemInserted(optographs.size() - 1);
            return;
        }

        // if optograph is oldest, simply append to list
        if (created_at != null && created_at.isBefore(getOldest().getCreated_atDateTime())) {
            optographs.add(optograph);
//            notifyItemInserted(getItemCount());
            notifyDataSetChanged();
            return;
        }

        // find correct position of optograph
        // TODO: allow for "breaks" between new optograph and others...
        for (int i = 0; i < optographs.size(); i++) {
            Optograph current = optographs.get(i);
            if (created_at != null && created_at.isAfter(current.getCreated_atDateTime())) {
                optographs.add(i, optograph);
                notifyItemInserted(i);
                return;
            }
        }
    }

    public void saveToSQLite(Optograph opto) {
        Cursor res = mydb.getData(opto.getId(), DBHelper.OPTO_TABLE_NAME_FEEDS, DBHelper.OPTOGRAPH_ID);
        res.moveToFirst();
        if (res.getCount()!=0) return;
        String loc = opto.getLocation()==null?"":opto.getLocation().getId();
        mydb.insertOptograph(opto.getId(), opto.getText(), opto.getPerson().getId(), opto.getLocation() == null ? "" : opto.getLocation().getId(),
                opto.getCreated_at(), opto.getDeleted_at() == null ? "" : opto.getDeleted_at(), opto.is_starred(), opto.getStars_count(), opto.is_published(),
                opto.is_private(), opto.getStitcher_version(), true, opto.is_on_server(), "", opto.isShould_be_published(), opto.is_local(),
                opto.is_place_holder_uploaded(), opto.isPostFacebook(), opto.isPostTwitter(), opto.isPostInstagram(),
                opto.is_data_uploaded(), opto.is_staff_picked(), opto.getShare_alias(), opto.getOptograph_type());
        res.close();
    }

    public void saveToSQLiteFeeds(Optograph opto) {

        if(opto.getId() == null) return;

        Cursor res1 = mydb.getData(opto.getId(), DBHelper.OPTO_TABLE_NAME_FEEDS, DBHelper.OPTOGRAPH_ID);
        res1.moveToFirst();
        if (res1.getCount() > 0) {
            String id = DBHelper.OPTOGRAPH_ID;
            String tb = DBHelper.OPTO_TABLE_NAME_FEEDS;
            if (opto.getText() != null && !opto.getText().equals("")) mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_TEXT, opto.getText());
            if (opto.getCreated_at() != null && !opto.getCreated_at().equals(""))
                mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_CREATED_AT, opto.getCreated_at());
            if (opto.getDeleted_at() != null && !opto.getDeleted_at().equals(""))
                mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_DELETED_AT, opto.getDeleted_at());
            mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_IS_STARRED, opto.is_starred());
            mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_STARS_COUNT, opto.getStars_count());
            mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_IS_PUBLISHED, opto.is_published());
            mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_IS_PRIVATE, opto.is_private());

            if (opto.getStitcher_version() != null && !opto.getStitcher_version().equals(""))
                mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_IS_STITCHER_VERSION, opto.getStitcher_version());
            if (opto.getStitcher_version() != null && !opto.getStitcher_version().equals(""))
                mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_IS_DATA_UPLOADED, opto.is_data_uploaded());

            mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_SHOULD_BE_PUBLISHED, opto.isShould_be_published());
            mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_IS_LOCAL, opto.is_local());
            mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_IS_PLACEHOLDER_UPLOADED, opto.is_place_holder_uploaded());
            mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_POST_FACEBOOK, opto.isPostFacebook());
            mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_POST_TWITTER, opto.isPostTwitter());
            mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_POST_INSTAGRAM, opto.isPostInstagram());
            mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_IS_STAFF_PICK, opto.is_staff_picked());
            mydb.updateTableColumn(tb, id, opto.getId(), DBHelper.OPTOGRAPH_SHARE_ALIAS, opto.getShare_alias());
            if (opto.getOptograph_type() != null && !opto.getOptograph_type().equals(""))
                mydb.updateTableColumn(tb, id, opto.getId(), "optograph_type", opto.getOptograph_type());
            if (opto.getLocation() != null && opto.getLocation().getId() != null && !opto.getLocation().getId().equals("")) {
                mydb.updateTableColumn(tb, id, opto.getId(), "optograph_location_id", opto.getLocation().getId());
            }
        } else {
            Timber.d("saveToSQLiteFeeds <= 0 " + opto.is_staff_picked() );
            mydb.insertOptograph(opto.getId(), opto.getText(), opto.getPerson().getId(), opto.getLocation() == null ? "" : opto.getLocation().getId(),
                    opto.getCreated_at(), opto.getDeleted_at() == null ? "" : opto.getDeleted_at(), opto.is_starred(), opto.getStars_count(), opto.is_published(),
                    opto.is_private(), opto.getStitcher_version(), true, opto.is_on_server(), "", opto.isShould_be_published(), opto.is_local(),
                    opto.is_place_holder_uploaded(), opto.isPostFacebook(), opto.isPostTwitter(), opto.isPostInstagram(),
                    opto.is_data_uploaded(), opto.is_staff_picked(), opto.getShare_alias(), opto.getOptograph_type());
        }

        res1.close();
        String loc = opto.getLocation() == null ? "" : opto.getLocation().getId();
        String per = opto.getPerson() == null ? "" : opto.getPerson().getId();

        if (!per.equals("")) {
            Cursor res2 = mydb.getData(opto.getPerson().getId(), DBHelper.PERSON_TABLE_NAME, "id");
            res2.moveToFirst();
            if (opto.getPerson().getId() != null) {
                Person person = opto.getPerson();
                if (res2.getCount() > 0) {
                    String id = "id";
                    String tb = DBHelper.PERSON_TABLE_NAME;
                    if (person.getCreated_at() != null && !person.getCreated_at().equals(""))
                        mydb.updateTableColumn(tb, id, person.getId(), "created_at", person.getCreated_at());
                    if (person.getDeleted_at() != null && !person.getDeleted_at().equals(""))
                        mydb.updateTableColumn(tb, id, person.getId(), "deleted_at", person.getDeleted_at());
                    if (person.getDisplay_name() != null && !person.getDisplay_name().equals(""))
                        mydb.updateTableColumn(tb, id, person.getId(), "display_name", person.getDisplay_name());
                    if (person.getUser_name() != null && !person.getUser_name().equals(""))
                        mydb.updateTableColumn(tb, id, person.getId(), "user_name", person.getUser_name());
                    if (person.getEmail() != null && !person.getEmail().equals(""))
                        mydb.updateTableColumn(tb, id, person.getId(), "email", person.getEmail());
                    if (person.getText() != null && !person.getText().equals(""))
                        mydb.updateTableColumn(tb, id, person.getId(), "text", person.getText());
                    if (person.getAvatar_asset_id() != null && !person.getAvatar_asset_id().equals(""))
                        mydb.updateTableColumn(tb, id, person.getId(), "avatar_asset_id", person.getAvatar_asset_id());

                    mydb.updateTableColumn(tb, id, person.getId(), "optographs_count", person.getOptographs_count());
                    mydb.updateTableColumn(tb, id, person.getId(), "followers_count", person.getFollowers_count());
                    mydb.updateTableColumn(tb, id, person.getId(), "followed_count", person.getFollowed_count());
                    mydb.updateTableColumn(tb, id, person.getId(), "is_followed", person.is_followed());

                    if (person.getFacebook_user_id() != null && !person.getFacebook_user_id().equals(""))
                        mydb.updateTableColumn(tb, id, person.getId(), "facebook_user_id", person.getFacebook_user_id());
                    if (person.getFacebook_token() != null && !person.getFacebook_token().equals(""))
                        mydb.updateTableColumn(tb, id, person.getId(), "facebook_token", person.getFacebook_token());
                    if (person.getTwitter_token() != null && !person.getTwitter_token().equals(""))
                        mydb.updateTableColumn(tb, id, person.getId(), "twitter_token", person.getTwitter_token());
                    if (person.getTwitter_secret() != null && !person.getTwitter_secret().equals(""))
                        mydb.updateTableColumn(tb, id, person.getId(), "twitter_secret", person.getTwitter_secret());

                } else {
                    mydb.insertPerson(person.getId(), person.getCreated_at(), person.getEmail(), person.getDeleted_at(), person.isElite_status(),
                            person.getDisplay_name(), person.getUser_name(), person.getText(), person.getAvatar_asset_id(), person.getFacebook_user_id(), person.getOptographs_count(),
                            person.getFollowers_count(), person.getFollowed_count(), person.is_followed(), person.getFacebook_token(), person.getTwitter_token(), person.getTwitter_secret());
                }
            }

            res2.close();
        }

        if (!loc.equals("")) {
            Cursor res3 = mydb.getData(opto.getLocation().getId(), DBHelper.LOCATION_TABLE_NAME, "id");
            res3.moveToFirst();
            if (opto.getLocation().getId() != null) {
                Location locs = opto.getLocation();
                if (res3.getCount() > 0) {
                    String id = "id";
                    String tb = DBHelper.LOCATION_TABLE_NAME;
                    if (locs.getCreated_at() != null && !locs.getCreated_at().equals(""))
                        mydb.updateTableColumn(tb, id, locs.getId(), "created_at", locs.getCreated_at());
                    if (locs.getUpdated_at() != null && !locs.getUpdated_at().equals(""))
                        mydb.updateTableColumn(tb, id, locs.getId(), "updated_at", locs.getUpdated_at());
                    if (locs.getDeleted_at() != null && !locs.getDeleted_at().equals(""))
                        mydb.updateTableColumn(tb, id, locs.getId(), "deleted_at", locs.getDeleted_at());
                    if (locs.getLatitude() != 0)
                        mydb.updateTableColumn(tb, id, locs.getId(), "latitude", locs.getLatitude());
                    if (locs.getLongitude() != 0)
                        mydb.updateTableColumn(tb, id, locs.getId(), "longitude", locs.getLongitude());
                    if (locs.getText() != null && !locs.getText().equals(""))
                        mydb.updateTableColumn(tb, id, locs.getId(), "text", locs.getText());
                    if (locs.getCountry() != null && !locs.getCountry().equals(""))
                        mydb.updateTableColumn(tb, id, locs.getId(), "country", locs.getCountry());
                    if (locs.getCountry_short() != null && !locs.getCountry_short().equals(""))
                        mydb.updateTableColumn(tb, id, locs.getId(), "country_short", locs.getCountry_short());
                    if (locs.getPlace() != null && !locs.getPlace().equals(""))
                        mydb.updateTableColumn(tb, id, locs.getId(), "place", locs.getPlace());
                    if (locs.getRegion() != null && !locs.getRegion().equals(""))
                        mydb.updateTableColumn(tb, id, locs.getId(), "region", locs.getRegion());
                    mydb.updateTableColumn(tb, id, locs.getId(), "poi", String.valueOf(locs.isPoi()));
                } else {
//                    mydb.updateColumnOptograph(opto.getId(),DBHelper.LOCATION_ID,locs.getId());
                    mydb.insertLocation(locs.getId(), locs.getCreated_at(), locs.getUpdated_at(), locs.getDeleted_at(), locs.getLatitude(), locs.getLongitude(), locs.getCountry(), locs.getText(),
                            locs.getCountry_short(), locs.getPlace(), locs.getRegion(), locs.isPoi());
                }
            }
            res3.close();
        }

    }

    public Optograph get(int position) {
        return optographs.get(position);
    }

    public Optograph getOldest() {
        return get(getItemCount() - 1);
    }

    public boolean isEmpty() {
        return optographs.isEmpty();
    }

    public List<Optograph> getOptographs() {
        return this.optographs;
    }

    public void disableDraggingPage(int position) {
        this.draggingPage = false;
        notifyItemRangeChanged(position - 1, 3, "test");
    }

    public void refreshAfterDelete(String id, boolean isLocal) {
        Timber.d("refreshAfterDelete");
        for (Optograph opto:optographs) {
            if (opto!=null && opto.getId().equals(id) && opto.is_local()==isLocal) {
                int position = optographs.indexOf(opto);
                optographs.remove(opto);
                notifyItemRemoved(position);
                Timber.d("refreshAfterDelete optoremoved");
                return;
            }
        }
    }

    public void refreshAllOptoThatContainsPersonId(String personId, boolean isFollowed, OptographHolder holder) {
        Timber.d("refreshAllOptoThatContainsPersonId");

        for(int position = 0; position < optographs.size(); position++) {
            if(optographs.get(position).getPerson().getId().equals(personId)) {
                optographs.get(position).getPerson().setIs_followed(isFollowed);

                if(isFollowed) {
                    optographs.get(position).getPerson().setFollowers_count(optographs.get(position).getPerson().getFollowers_count() + 1);
                    holder.getBinding().follow.setImageResource(R.drawable.feed_following_icn);
                } else {
                    optographs.get(position).getPerson().setFollowers_count(optographs.get(position).getPerson().getFollowers_count() - 1);
                    holder.getBinding().follow.setImageResource(R.drawable.feed_follow_icn);
                }

                notifyItemChanged(position);
            }

        }

    }

    public static class OptographHolder extends RecyclerView.ViewHolder {
        private NewFeedItemBinding bindingHeader;

        public OptographHolder(View rowView, Context context) {
            super(rowView);
            this.bindingHeader = DataBindingUtil.bind(rowView);
        }

        public NewFeedItemBinding getBinding() {
            return bindingHeader;
        }
    }

}