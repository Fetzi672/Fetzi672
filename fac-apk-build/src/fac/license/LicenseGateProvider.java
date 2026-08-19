package fac.license;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * V9 compatibility provider.
 *
 * Kept only because older V8 manifests may still reference this class during
 * the transition. It performs no gating and never terminates the process.
 * All V9 gating/handoff logic lives in LicenseActivity + SessionHandoff.
 */
public class LicenseGateProvider extends ContentProvider {
    public static final String COLD_GRANT="unused_v9";
    @Override public boolean onCreate(){ return true; }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String so){return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){return null;}
    @Override public int delete(Uri u,String s,String[] a){return 0;}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
