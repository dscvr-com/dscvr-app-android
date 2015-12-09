package co.optonaut.optonaut.views;

import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;

/**
 * @author Nilan Marktanner
 * @date 2015-12-09
 */
public class BaseFragment extends Fragment {
    public static boolean handleBackPressed(FragmentManager fm)
    {
        if(fm.getFragments() != null){
            for(Fragment frag : fm.getFragments()){
                if(frag != null && frag.isVisible() && frag instanceof BaseFragment){
                    if(((BaseFragment)frag).onBackPressed()){
                        return true;
                    }
                }
            }
        }
        return false;
    }

    protected boolean onBackPressed()
    {
        FragmentManager fm = getChildFragmentManager();
        if(handleBackPressed(fm)){
            return true;
        }
        else if(getUserVisibleHint() && fm.getBackStackEntryCount() > 0){
            fm.popBackStack();
            return true;
        }
        return false;
    }
}
