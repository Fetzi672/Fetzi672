package fac.license.ui;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import android.text.InputType;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class BotSettingsActivity extends Activity {
    private static final File CONFIG=new File("/storage/emulated/0/uix/zh1.txt");
    private static final int MAX=512*1024;
    private JSONArray schema;
    private JSONObject document;
    private Spinner categorySpinner;
    private LinearLayout fields;
    private final LinkedHashMap<String,View> controls=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> types=new LinkedHashMap<>();
    private final LinkedHashMap<String,JSONArray> options=new LinkedHashMap<>();
    private final ArrayList<String> categories=new ArrayList<>();
    private byte[] loadedBytes;
    private String loadedHash;

    @Override public void onCreate(Bundle b){ super.onCreate(b); if(!getSharedPreferences("fac_license",0).getBoolean("verified",false)){ new AlertDialog.Builder(this).setTitle("License required").setMessage("Verify your FAC license first.").setPositiveButton("OK",(d,w)->finish()).show(); return; } buildUi(); load(); }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);} 
    private TextView tv(String s,int sp){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(sp);return t;}
    private void buildUi(){ LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(16),dp(16),dp(16));root.setBackgroundColor(Color.rgb(20,20,24)); TextView h=tv("Bot Settings",24);root.addView(h);TextView p=tv("Edits /storage/emulated/0/uix/zh1.txt. Changes are only written when SAVE & APPLY is pressed.",12);p.setTextColor(Color.LTGRAY);p.setPadding(0,dp(6),0,dp(12));root.addView(p);categorySpinner=new Spinner(this);root.addView(categorySpinner,new LinearLayout.LayoutParams(-1,dp(52))); ScrollView s=new ScrollView(this);fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);s.addView(fields);root.addView(s,new LinearLayout.LayoutParams(-1,0,1));Button save=new Button(this);save.setText("SAVE & APPLY");save.setOnClickListener(v->save());root.addView(save,new LinearLayout.LayoutParams(-1,dp(54)));setContentView(root); }
    private void load(){ try{ schema=new JSONArray(readAsset("fac/bot_config_schema.json")); if(!CONFIG.isFile())throw new IOException("Configuration file not present."); if(CONFIG.length()>MAX)throw new IOException("Configuration file exceeds 512 KiB."); loadedBytes=readFile(CONFIG); loadedHash=sha256(loadedBytes); String txt=new String(loadedBytes,StandardCharsets.UTF_8); if(txt.length()>0 && txt.charAt(0)=='\ufeff')throw new IOException("UTF-8 BOM is not allowed."); document=new JSONObject(txt); buildCategoryList(); }catch(Exception e){ error(e.getMessage()==null?"Configuration could not be loaded.":e.getMessage()); } }
    private void buildCategoryList()throws Exception{ categories.clear();HashSet<String> seen=new HashSet<>();for(int i=0;i<schema.length();i++){JSONObject f=schema.getJSONObject(i);String c=f.getString("category");if(seen.add(c))categories.add(c);}ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,categories);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);categorySpinner.setAdapter(a);categorySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){} public void onItemSelected(android.widget.AdapterView<?>p,View v,int pos,long id){renderCategory(categories.get(pos));}});if(!categories.isEmpty())renderCategory(categories.get(0));}
    private void renderCategory(String cat){fields.removeAllViews();controls.clear();types.clear();options.clear();try{for(int i=0;i<schema.length();i++){JSONObject f=schema.getJSONObject(i);if(!cat.equals(f.getString("category")))continue;String key=f.getString("key"),type=f.getString("type"),label=f.getString("label");String value=document.optString(key,"");TextView l=tv(label,14);l.setPadding(0,dp(10),0,dp(4));fields.addView(l);View control;if("BOOLEAN".equals(type)){Switch sw=new Switch(this);sw.setText(value);sw.setTextColor(Color.LTGRAY);sw.setChecked(isTrue(value));sw.setOnCheckedChangeListener((b,c)->b.setText(c?"true":"false"));control=sw;}else if("SELECTOR".equals(type)){JSONArray opts=f.optJSONArray("options");ArrayList<String> vals=new ArrayList<>();if(opts!=null)for(int j=0;j<opts.length();j++)vals.add(opts.optString(j));if(vals.isEmpty())vals.add(value);Spinner sp=new Spinner(this);ArrayAdapter<String> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,vals);ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);sp.setAdapter(ad);int ix=vals.indexOf(value);if(ix>=0)sp.setSelection(ix);control=sp;options.put(key,opts);}else{EditText e=new EditText(this);e.setText(value);e.setTextColor(Color.WHITE);e.setSingleLine(!"TEXT".equals(type));if("INTEGER".equals(type))e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);else if("DECIMAL".equals(type))e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);else e.setInputType(InputType.TYPE_CLASS_TEXT);control=e;}fields.addView(control,new LinearLayout.LayoutParams(-1,dp("TEXT".equals(type)?72:52)));controls.put(key,control);types.put(key,type);} }catch(Exception e){error("Could not render this category.");}}
    private boolean isTrue(String s){return "1".equals(s)||"true".equalsIgnoreCase(s)||"on".equalsIgnoreCase(s)||"yes".equalsIgnoreCase(s);}
    private String value(View v,String type){if(v instanceof Switch)return ((Switch)v).isChecked()?"true":"false";if(v instanceof Spinner){Object x=((Spinner)v).getSelectedItem();return x==null?"":x.toString();}return ((EditText)v).getText().toString();}
    private void save(){try{if(document==null)throw new IOException("Configuration is not loaded.");for(Map.Entry<String,View> e:controls.entrySet())document.put(e.getKey(),value(e.getValue(),types.get(e.getKey())));byte[] fresh=readFile(CONFIG);if(!sha256(fresh).equals(loadedHash))throw new IOException("Configuration changed since it was loaded. Reload before saving.");byte[] next=document.toString().getBytes(StandardCharsets.UTF_8);if(next.length>MAX)throw new IOException("Result exceeds 512 KiB.");File dir=CONFIG.getParentFile();File tmp=new File(dir,"zh1.txt.tmp-"+UUID.randomUUID());File bak=new File(dir,"zh1.txt.fac-bak");writeSync(tmp,next);if(bak.exists())bak.delete();if(!CONFIG.renameTo(bak))throw new IOException("Could not create transactional backup.");if(!tmp.renameTo(CONFIG)){bak.renameTo(CONFIG);throw new IOException("Atomic replace failed.");}byte[] verify=readFile(CONFIG);if(!sha256(verify).equals(sha256(next))){CONFIG.delete();bak.renameTo(CONFIG);throw new IOException("Post-write verification failed; rollback completed.");}bak.delete();loadedBytes=verify;loadedHash=sha256(verify);Toast.makeText(this,"Saved and applied.",Toast.LENGTH_SHORT).show();}catch(Exception e){error(e.getMessage()==null?"Save failed.":e.getMessage());}}
    private String readAsset(String p)throws Exception{InputStream in=getAssets().open(p);ByteArrayOutputStream o=new ByteArrayOutputStream();byte[]b=new byte[8192];int n;while((n=in.read(b))>0)o.write(b,0,n);in.close();return new String(o.toByteArray(),StandardCharsets.UTF_8);} 
    private byte[] readFile(File f)throws Exception{FileInputStream in=new FileInputStream(f);ByteArrayOutputStream o=new ByteArrayOutputStream();byte[]b=new byte[8192];int n,total=0;while((n=in.read(b))>0){total+=n;if(total>MAX)throw new IOException("Configuration file exceeds 512 KiB.");o.write(b,0,n);}in.close();return o.toByteArray();}
    private void writeSync(File f,byte[]d)throws Exception{FileOutputStream o=new FileOutputStream(f);o.write(d);o.flush();o.getFD().sync();o.close();}
    private String sha256(byte[]d)throws Exception{byte[]h=MessageDigest.getInstance("SHA-256").digest(d);StringBuilder s=new StringBuilder();for(byte x:h)s.append(String.format(Locale.US,"%02x",x&255));return s.toString();}
    private void error(String m){new AlertDialog.Builder(this).setTitle("Bot Settings").setMessage(m).setPositiveButton("OK",null).show();}
}
