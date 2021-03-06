package org.sipdroid.sipua.ui;

/*
 * Copyright (C) 2009 The Sipdroid Open Source Project
 * 
 * This file is part of Sipdroid (http://www.sipdroid.org)
 * 
 * Sipdroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

import java.util.List;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.sipdroid.media.RtpStreamReceiver;
import org.lumicall.android.R;
import org.lumicall.android.db.LumicallDataSource;
import org.lumicall.android.db.SIPIdentity;
import org.lumicall.android.sip.ENUMProviderForSIP;
import org.lumicall.android.sip.ENUMUtil;
import org.lumicall.android.sip.EmailCandidateHarvester;
import org.lumicall.android.sip.DialCandidate;
import org.lumicall.android.sip.HarvestDirector;
import org.sipdroid.sipua.UserAgent;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Contacts;
import android.provider.Contacts.People;
import android.provider.Contacts.Phones;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

public class Caller extends BroadcastReceiver {

		private static final int REDIAL_MINIMUM_INTERVAL = 3000;
		private static final String TAG = "Caller";
		static long noexclude;
		String last_number;
		long last_time;
		
		public class ChooserThread extends Thread {
			DialCandidate[] candidates;
			Context context;
			public ChooserThread(Context context, DialCandidate[] candidates) {
				this.context = context;
				this.candidates = candidates;
			}
			public void run() {
				try {
					Thread.sleep(200);
				} catch (InterruptedException e) {
				}
		        Intent intent = new Intent(Intent.ACTION_CALL,
		                Uri.fromParts(Settings.URI_SCHEME, Uri.decode(candidates[0].getAddress()), null));
		        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		        intent.putExtra("dialCandidates", candidates);
		        context.startActivity(intent);					
			}
        }
		
		@Override
		public void onReceive(final Context context, Intent intent) {
	        String intentAction = intent.getAction();
	        String number = getResultData();
	        Boolean force = false;
	        
	        // This is quite an interesting area to log as a lot of
	        // decisions are made about call routing, so let's start with
	        // something to draw attention to it:
	        Log.d("SipUA:", "Caller.onReceive *****************************************************************************************************");
	        
	        if (intentAction.equals(Intent.ACTION_NEW_OUTGOING_CALL) && number != null)
	        {
        		if (!Sipdroid.release)
        			Log.i("SipUA:","outgoing call");
        		
        		// is sipdroid enabled?
        		if (!Sipdroid.on(context)) {
        			Log.d("SipUA:", "sipdroid not on");
        			return;
        		}
        		
        		String uriFragment = Uri.parse(
        	            intent.getStringExtra("android.phone.extra.ORIGINAL_URI")).getFragment();
        		if(uriFragment != null && uriFragment.contains(PSTN.BYPASS_LUMICALL)) {
        			// Let the call go through to the next handler/GSM network
        			Log.i(TAG, "*** Lumicall detected `lumicall-bypass' in URI, letting call go to next handler ***");
        			setResultData(number);
        			abortBroadcast();
        			return;
	        	}
        		
        		
        		// If the user has chosen a GSM route in the chooser, we should
        		// not do anything, just let the next handler deal with it
        		if(number.endsWith("?p")) {
        			setResultData(number.substring(0, number.indexOf('?')));
        			return;
        		}
        		
    			boolean sip_type = !PreferenceManager.getDefaultSharedPreferences(context).getString(Settings.PREF_PREF, Settings.DEFAULT_PREF).equals(Settings.VAL_PREF_PSTN);
    	        boolean ask = PreferenceManager.getDefaultSharedPreferences(context).getString(Settings.PREF_PREF, Settings.DEFAULT_PREF).equals(Settings.VAL_PREF_ASK);
    	        
      	        if (Receiver.call_state != UserAgent.UA_STATE_IDLE && RtpStreamReceiver.isBluetoothAvailable()) {
       	        	setResultData(null);
       	        	switch (Receiver.call_state) {
    	        	case UserAgent.UA_STATE_INCOMING_CALL:
    	        		Receiver.engine(context).answercall();
    	        		if (RtpStreamReceiver.bluetoothmode)
    	        			break;
    	        	default:
    	        		if (RtpStreamReceiver.bluetoothmode)
    	        			Receiver.engine(context).rejectcall();
    	        		else
    	        			Receiver.engine(context).togglebluetooth();
    	        		break;	
       	        	}
       	        	return;
      	        }
      	        
      	        // Don't redial without required interval between attempts
    	        if (last_number != null && last_number.equals(number) && (SystemClock.elapsedRealtime()-last_time) < REDIAL_MINIMUM_INTERVAL) {
    	        	setResultData(null);
    	        	abortBroadcast();
    	        	Log.w("SipUA:", "redial was too soon, aborted");
    	        	return;
    	        }
      	        last_time = SystemClock.elapsedRealtime();
    	        last_number = number;
    	        
    	        // Is the user over-riding the default network choice?
 				if (number.endsWith("+")) 
    			{
    				sip_type = !sip_type;
    				number = number.substring(0,number.length()-1);
    				force = true;
    			}
 				
				if (SystemClock.elapsedRealtime() < noexclude + 10000) {
					noexclude = 0;
					force = true;
				}
				
				String e164Number = null;
				if (number.indexOf('@') > 0) {
					// The target contains the @ symbol, treat it as a SIP address
					// NOTE: When somebody dials a SIP URI, it will usually not end up
					// in this receiver at all, the Intent generated by the platform will
					// launch the SIPUri Activity and the logic below is duplicated in that class.
					String _domain = number.substring(number.indexOf('@') + 1);
					LumicallDataSource ds = new LumicallDataSource(context);
					ds.open();
					
					List<SIPIdentity> sipIdentities = ds.getSIPIdentities();
					
					SIPIdentity sipIdentity = null;
					for(SIPIdentity s : sipIdentities) {
						String uri = s.getUri();
						String domain = uri.substring(uri.indexOf('@') + 1);
						if(domain.equals(_domain))
						{
							sipIdentity = s;
							Log.d(TAG, "matched domain: " + domain + ", using identity: " + s.getUri());
						}
					}
					
					ds.close();
					DialCandidate dc = new DialCandidate("sip", number, "", "Manual dial", sipIdentity);
					if(!dc.call(context)) {
						// ignoring error
					}
					setResultData(null);
					abortBroadcast();
					return;
				} else if(number.startsWith("+")) {
					// Just assume it is an E.164 number already
					e164Number = number;
				} else {
					// Try and convert to an E.164 number
					PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
					try {
						// FIXME - should prompt the user to check the number
						// FIXME - should update the contact DB
						TelephonyManager mTelephonyMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
						String countryIsoCode = mTelephonyMgr.getSimCountryIso().toUpperCase();
						Log.v(TAG, "Converting number: " + number + ", country ISO = " + countryIsoCode);
						PhoneNumber numberProto = phoneUtil.parse(number, countryIsoCode);
						if(phoneUtil.isValidNumber(numberProto))
							e164Number = phoneUtil.format(numberProto, PhoneNumberFormat.E164);
					} catch (NumberParseException e) {
						Log.w(TAG, "Error parsing number", e);
					}
				}
				
				HarvestDirector hd = new HarvestDirector();
				List<DialCandidate> candidates = hd.getCandidates(context, number, e164Number);
				
				if(candidates.size() == 0) {
					setResultData(null);
					abortBroadcast();
					return;
				}
				
				DialCandidate target = candidates.get(0);
				if(candidates.size() > 1) {
					// Display a popup for the user to choose a candidate
					
					(new ChooserThread(context, candidates.toArray(new DialCandidate[] {}))).start();  
					setResultData(null);
					abortBroadcast();
					return;
				}
				
				// Only 1 candidate - so just dial it
				if(!candidates.get(0).call(context)) {
					// ignore error
				}
				if(true) {
					setResultData(null);
					abortBroadcast();
					return;
				}
				
				/* if(doENUMRouting(context, number)) {
					setResultData(null);
					return;
				} */
				
				// Look for numbers that are excluded from SIP routing
				// e.g. user can exclude SIP routing for calls
				// to PSTN voicemail number
				if (sip_type && !force) {
	    			//String sExPat = PreferenceManager.getDefaultSharedPreferences(context).getString(Settings.PREF_EXCLUDEPAT, Settings.DEFAULT_EXCLUDEPAT);
					String sExPat = "";
	   				boolean bExNums = false;
					boolean bExTypes = false;
					if (sExPat.length() > 0) 
					{					
						Vector<String> vExPats = getTokens(sExPat, ",");
						Vector<String> vPatNums = new Vector<String>();
						Vector<Integer> vTypesCode = new Vector<Integer>();					
				    	for(int i = 0; i < vExPats.size(); i++)
			            {
				    		if (vExPats.get(i).startsWith("h") || vExPats.get(i).startsWith("H"))
			        			vTypesCode.add(Integer.valueOf(People.Phones.TYPE_HOME));
				    		else if (vExPats.get(i).startsWith("m") || vExPats.get(i).startsWith("M"))
			        			vTypesCode.add(Integer.valueOf(People.Phones.TYPE_MOBILE));
				    		else if (vExPats.get(i).startsWith("w") || vExPats.get(i).startsWith("W"))
			        			vTypesCode.add(Integer.valueOf(People.Phones.TYPE_WORK));
				    		else 
				    			vPatNums.add(vExPats.get(i));     
			            }
						if(vTypesCode.size() > 0)
							bExTypes = isExcludedType(vTypesCode, number, context);
						if(vPatNums.size() > 0)
							bExNums = isExcludedNum(vPatNums, number);   					
					}	
					if (bExTypes || bExNums)
						sip_type = false;
				}

    			if (!sip_type) {
    				// PSTN call - let the normal handler dial it
    				setResultData(number);
    			} else {
    				// SIP call - start the dial process
	        		if (number != null && !intent.getBooleanExtra("android.phone.extra.ALREADY_CALLED",false)) {

	        		    	SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
		        				        		    	
	        		    	// Search & replace.
	    				// String search = sp.getString(Settings.PREF_SEARCH, Settings.DEFAULT_SEARCH);
		        		String search = "";
	    				// String callthru_number = searchReplaceNumber(search, number);
		        		String callthru_number = number;
	    				String callthru_prefix;
	    				
	    				// if "par" is true, get all numbers from the contact, concatenate with "&"
	    				// boolean par = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(Settings.PREF_PAR, Settings.DEFAULT_PAR);
	    				boolean par = false;  // SIP forking / parallel dialing
	    				if (!ask && !force && par) {
							number = concatenateNumbers(context, number, callthru_number, search);
	    				} else
	    					number = callthru_number;
						
						if (PreferenceManager.getDefaultSharedPreferences(context).getString(Settings.PREF_PREF, Settings.DEFAULT_PREF).equals(Settings.VAL_PREF_SIPONLY))
							force = true;
	    				if (!ask && Receiver.engine(context).call(number,force))
	    					setResultData(null);
	    				else if (ask || force) {
	    					// Try the call another way?
	    					setResultData(null);
	    					final String n = number;
	    			        (new Thread() {
	    						public void run() {
			    					try {
										Thread.sleep(200);
									} catch (InterruptedException e) {
									}
			    			        Intent intent = new Intent(Intent.ACTION_CALL,
			    			                Uri.fromParts(Settings.URI_SCHEME, Uri.decode(n), null));
			    			        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			    			        context.startActivity(intent);					
	    						}
	    			        }).start();  
	    				}
	        		}
	            }
	        }
	    }

		// Try to set up a call to the number by using ENUM
		private boolean doENUMRouting(Context context, String number) {
			
			if(number == null)
				return false;
			
			boolean online = ENUMUtil.updateNotification(context);
			
			if(!online) {
				Log.i("SipUA:", "ENUM not online");
				return false;
			}
			
			if(!number.startsWith("+")) {
				Log.w("SipUA:", "can't handle non-E.164 numbers yet");
				return false;  // FIXME: translate numbers to E.164 format
			}
			
			Toast toast = Toast.makeText(context, R.string.toast_progress, Toast.LENGTH_SHORT);
			toast.show();

			/* ask the ENUM ContentProvider for the records */
			Uri uri = Uri.withAppendedPath(ENUMProviderForSIP.CONTENT_URI, number);
			ContentResolver cr = context.getContentResolver();
			Cursor mCursor = cr.query(uri, null, null, null, null);
			
			/* none found - tell the user then dial the original number */
			if (mCursor == null || mCursor.getCount() <= 0) {
				if(mCursor != null)
					mCursor.close();
				toast.setText(R.string.toast_notfound);
				toast.show();
				Log.i("SipUA:", "no ENUM result found, falling through to next routing choice");
				return false;
			}

			toast.cancel();
			
			mCursor.moveToFirst();
			String destination = mCursor.getString(2);
			mCursor.close();
			
			Log.v("SipUA:", "ENUM result found, dialing SIP destination: " + destination);
			
			return Receiver.engine(context).call(destination, true);
		}
		
		
		private String concatenateNumbers(Context context, String _number, String callthru_number, String search) {
			String number = _number;
			/*	    					String orig = intent.getStringExtra("android.phone.extra.ORIGINAL_URI");	
				if (orig.lastIndexOf("/phones") >= 0) 
			{
					orig = orig.substring(0,orig.lastIndexOf("/phones")+7);
				Uri contactRef = Uri.parse(orig);
			 */
			Uri contactRef = Uri.withAppendedPath(Contacts.Phones.CONTENT_FILTER_URL, number);
			final String[] PHONES_PROJECTION = new String[] {
					People.Phones.NUMBER, // 0
					People.Phones.TYPE, // 1
			};
			Cursor phonesCursor = context.getContentResolver().query(contactRef, PHONES_PROJECTION, null, null,
					Phones.ISPRIMARY + " DESC");
			if (phonesCursor != null) {	        			        	
				number = "";
				while (phonesCursor.moveToNext()) {
					final int type = phonesCursor.getInt(1);
					String n = phonesCursor.getString(0);
					if (TextUtils.isEmpty(n))
						continue;
					if (type == Phones.TYPE_MOBILE || type == Phones.TYPE_HOME || type == Phones.TYPE_WORK) {
						if (!number.equals(""))
							number = number + "&";
						n = PhoneNumberUtils.stripSeparators(n);
						number = number + searchReplaceNumber(search, n);
					}
				}
				phonesCursor.close();
				if (number.equals(""))
					number = callthru_number;
			} else
				number = callthru_number;
			//			}			
			return number;
		}
		
		private String searchReplaceNumber(String pattern, String number) {
		    // Comma should be safe as separator.
		    String[] split = pattern.split(",");
		    // We need exactly 2 parts: search and replace. Otherwise
		    // we just return the current number.
		    if (split.length != 2)
			return number;

		    String modNumber = split[1];
		    
		    try {
			// Compiles the regular expression. This could be done
			// when the user modify the pattern... TODO Optimize
			// this, only compile once.
			Pattern p = Pattern.compile(split[0]);
    		    	Matcher m = p.matcher(number);
    		    	// Main loop of the function.
    		    	if (m.matches()) {
    		    	    for (int i = 0; i < m.groupCount() + 1; i++) {
    		    		String r = m.group(i);
    		    		if (r != null) {
    		    		    modNumber = modNumber.replace("\\" + i, r);
    		    		}
    		    	    }
    		    	}
    		    	// If the modified number is the same as the replacement
    		    	// value, we guess that the user typed a bad replacement
    		    	// value and we use the original number.
    		    	if (modNumber.equals(split[1])) {
    		    	    modNumber = number;
    		    	}
		    } catch (PatternSyntaxException e) {
			// Wrong pattern syntax. Give back the original number.
			modNumber = number;
		    }
		    
		    // Returns the modified number.
		    return modNumber;
		}
	    
	    Vector<String> getTokens(String sInput, String sDelimiter)
	    {
	    	Vector<String> vTokens = new Vector<String>();				
			int iStartIndex = 0;				
			final int iEndIndex = sInput.lastIndexOf(sDelimiter);
			for (; iStartIndex < iEndIndex; iStartIndex++) 
			{
				int iNextIndex = sInput.indexOf(sDelimiter, iStartIndex);
				String sPattern = sInput.substring(iStartIndex, iNextIndex).trim();
				vTokens.add(sPattern);
				iStartIndex = iNextIndex; 
			}
			if(iStartIndex < sInput.length())
				vTokens.add(sInput.substring(iStartIndex, sInput.length()).trim());
		
			return vTokens;
	    }
	    
	    boolean isExcludedNum(Vector<String> vExNums, String sNumber)
	    {
			for (int i = 0; i < vExNums.size(); i++) 
			{
				Pattern p = null;
				Matcher m = null;
				try
				{					
					p = Pattern.compile(vExNums.get(i));
					m = p.matcher(sNumber);	
				}
				catch(PatternSyntaxException pse)
				{
		           return false;    
				}  
				if(m != null && m.find())
					return true;			
			}    		
			return false;
	    }
	    
	    boolean isExcludedType(Vector<Integer> vExTypesCode, String sNumber, Context oContext)
	    {
	    	Uri contactRef = Uri.withAppendedPath(Contacts.Phones.CONTENT_FILTER_URL, sNumber);
	    	final String[] PHONES_PROJECTION = new String[] 
		    {
		        People.Phones.NUMBER, // 0
		        People.Phones.TYPE, // 1
		    };
	        Cursor phonesCursor = oContext.getContentResolver().query(contactRef, PHONES_PROJECTION, null, null,
	                null);
			if (phonesCursor != null) 
	        {	        			
 	            while (phonesCursor.moveToNext()) 
	            { 			            	
	                final int type = phonesCursor.getInt(1);	              
	                if(vExTypesCode.contains(Integer.valueOf(type)))
	                	return true;	    
	            }
	            phonesCursor.close();
	        }
			return false;
	    }   
	    
}
