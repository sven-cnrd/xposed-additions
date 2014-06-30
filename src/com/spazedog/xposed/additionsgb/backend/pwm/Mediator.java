package com.spazedog.xposed.additionsgb.backend.pwm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.spazedog.lib.reflecttools.ReflectClass;
import com.spazedog.lib.reflecttools.ReflectClass.OnErrorListener;
import com.spazedog.lib.reflecttools.ReflectConstructor;
import com.spazedog.lib.reflecttools.ReflectField;
import com.spazedog.lib.reflecttools.ReflectMethod;
import com.spazedog.lib.reflecttools.utils.ReflectConstants.Match;
import com.spazedog.lib.reflecttools.utils.ReflectException;
import com.spazedog.lib.reflecttools.utils.ReflectMember;
import com.spazedog.xposed.additionsgb.Common;
import com.spazedog.xposed.additionsgb.backend.PhoneWindowManager;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;

public final class Mediator {
	public static final String TAG = Mediator.class.getName();
	
	public static enum ActionType { CLICK, TAP, PRESS }
	
	/**
	 * A class containing different feature versions based on Gingerbread and up.
	 * If a method or feature has changed since Gingerbread, the version is bumped up to the amount of 
	 * changes from Gingerbread and to the current running Android version. For an example 
	 * the parameters in the intercept methods was changed in API 11. So for API 11 and up, we assign
	 * the version as 2.
	 */
	public static final class SDK {
		private static Integer calcSamsungAPI() {
			ReflectClass spwm = ReflectClass.forName("com.android.internal.policy.impl.sec.SamsungPhoneWindowManager", Match.SUPPRESS);
			
			if (spwm.exists()) {
				return spwm.findMethod("performSystemKeyFeedback", Match.SUPPRESS, KeyEvent.class).exists() ? 1 : 
					spwm.findMethod("performSystemKeyFeedback", Match.SUPPRESS, KeyEvent.class, Boolean.TYPE, Boolean.TYPE).exists() ? 2 : 0;
			}
			
			return 0;
		}
		
		private static Integer calcInputDeviceAIP() {
			ReflectClass id = ReflectClass.forName("android.view.InputDevice", Match.SUPPRESS);
			
			if (id.exists() && id.findMethod("isExternal", Match.SUPPRESS, KeyEvent.class).exists()) {
				return 2;
			}
			
			return 1;
		}
		
		/*
		 * In Jellybean Google added a new method for checking whether a device is external or internal.
		 * For some reason they have made this small method hidden, so we need reflection to use it. 
		 */
		public static final Integer INPUT_DEVICESTORAGE_VERSION = calcInputDeviceAIP();
		
		/*
		 * Newer Samsung devices uses an internal haptic feedback method with hardcoded keycodes. 
		 * At the same time, they have removed the virtual policy flag, so we need to use their method
		 * in order to get proper haptic control. 
		 * There are two different version of this method with different parameter numbers. 
		 * In version 2, the last one needs to be be true in order to allow haptic feedback. 
		 */
		public static final Integer SAMSUNG_FEEDBACK_VERSION = calcSamsungAPI();
		
		/*
		 * Parameter change in PhoneWindowManager.interceptKeyBefore* in API 11
		 */
		public static final Integer METHOD_INTERCEPT_VERSION = android.os.Build.VERSION.SDK_INT >= 11 ? 2 : 1;
		
		/*
		 * Input management was moved from the Window Manager Service into it's own Input Manager class in API 16.
		 */
		public static final Integer MANAGER_HARDWAREINPUT_VERSION = android.os.Build.VERSION.SDK_INT >= 16 ? 2 : 1;
		
		/*
		 * In API 19 Android switched from the KeyguardMediator class to a new KeyguardDelegate class.
		 */
		public static final Integer MANAGER_KEYGUARD_VERSION = android.os.Build.VERSION.SDK_INT >= 19 ? 2 : 1;
		
		/*
		 * New tools to turn on the screen was added to the documented part in API 17.
		 * In older versions it can only be done using forceUserActivityLocked() from the PowerManagerService using reflection.
		 */
		public static final Integer MANAGER_POWER_VERSION = android.os.Build.VERSION.SDK_INT >= 17 ? 2 : 1;
		
		/*
		 * Some of the character map values are missing in API below 11, such as VirtualKey for an example.
		 */
		public static final Integer INPUT_CHARACTERMAP_VERSION = android.os.Build.VERSION.SDK_INT >= 11 ? 2 : 1;
	}
	
	/**
	 * A class containing values from original properties
	 */
	public static final class ORIGINAL {
		public static Integer FLAG_INJECTED;
		public static Integer FLAG_VIRTUAL;
		public static Integer FLAG_WAKE;
		
		public static Integer QUEUEING_ALLOW;
		public static Integer QUEUEING_REJECT;
		
		public static Object DISPATCHING_ALLOW;
		public static Object DISPATCHING_REJECT;
		
		public static Integer INPUT_MODE_ASYNC;
	}
	
	protected Handler mHandler;
	
	private XServiceManager mXServiceManager;
	
	private ReflectClass mContext;
	private ReflectClass mPhoneWindowManager; 					// com.android.internal.policy.impl.PhoneWindowManager
	private ReflectClass mSamsungPhoneWindowManager; 			// com.android.internal.policy.impl.sec.SamsungPhoneWindowManager
	private ReflectClass mWindowManagerService;					// android.view.IWindowManager (com.android.server.wm.WindowManagerService)
	private ReflectClass mKeyguardMediator;						// com.android.internal.policy.impl.keyguard.KeyguardServiceDelegate or com.android.internal.policy.impl.KeyguardViewMediator
	private ReflectClass mActivityManager;						// android.app.ActivityManager
	private ReflectClass mActivityManagerService;				// android.app.IActivityManager (android.app.ActivityManagerNative)
	private ReflectClass mPowerManager;							// android.os.PowerManager
	private ReflectClass mPowerManagerService;					// android.os.IPowerManager (com.android.server.power.PowerManagerService)
	private ReflectClass mInputManager;							// android.hardware.input.InputManager
	private ReflectClass mAudioManager;
	
	private Boolean mReady = false;
	
	private Map<String, ReflectConstructor> mConstructors = new HashMap<String, ReflectConstructor>();
	private Map<String, ReflectMethod> mMethods = new HashMap<String, ReflectMethod>();
	private Map<String, ReflectField> mFields = new HashMap<String, ReflectField>();
	
	protected Mediator(ReflectClass pwm, XServiceManager xManager) {
		mXServiceManager = xManager;
		mContext = pwm.findFieldDeep("mContext").getValueToInstance();
		mPhoneWindowManager = pwm;
		mHandler = new Handler();
		
		/*
		 * Get all needed original property values
		 */
		ReflectClass wmp = ReflectClass.forName("android.view.WindowManagerPolicy");
		
		ORIGINAL.FLAG_INJECTED = (Integer) wmp.findField("FLAG_INJECTED").getValue();
		ORIGINAL.FLAG_VIRTUAL = (Integer) wmp.findField("FLAG_VIRTUAL").getValue();
		ORIGINAL.FLAG_WAKE = (Integer) ((wmp.findField("FLAG_WAKE").getValue())) | (Integer) ((wmp.findField("FLAG_WAKE_DROPPED").getValue()));
		
		ORIGINAL.QUEUEING_ALLOW = (Integer) wmp.findFieldDeep("ACTION_PASS_TO_USER").getValue();
		ORIGINAL.QUEUEING_REJECT = 0;
		
		ORIGINAL.DISPATCHING_ALLOW = SDK.METHOD_INTERCEPT_VERSION == 1 ? false : 0;
		ORIGINAL.DISPATCHING_REJECT = SDK.METHOD_INTERCEPT_VERSION == 1 ? true : -1;
		
		if (SDK.MANAGER_HARDWAREINPUT_VERSION > 1) {
			ORIGINAL.INPUT_MODE_ASYNC = (Integer) ReflectClass.forName("android.hardware.input.InputManager").findField("INJECT_INPUT_EVENT_MODE_ASYNC").getValue();
		}
		
		/*
		 * Get the Samsung specific haptic feedback methods
		 */
		if (SDK.SAMSUNG_FEEDBACK_VERSION > 0) {
			/*
			 * The instance of com.android.internal.policy.impl.sec.SamsungPhoneWindowManager
			 * is located at com.android.internal.policy.impl.PhoneWindowManager$mSPWM
			 */
			mSamsungPhoneWindowManager = pwm.findField("mSPWM").getValueToInstance();
			
			if (SDK.SAMSUNG_FEEDBACK_VERSION == 1) {
				mMethods.put("samsung.performSystemKeyFeedback", mSamsungPhoneWindowManager.findMethod("performSystemKeyFeedback", Match.DEFAULT, KeyEvent.class));
				
			} else {
				mMethods.put("samsung.performSystemKeyFeedback", mSamsungPhoneWindowManager.findMethod("performSystemKeyFeedback", Match.DEFAULT, KeyEvent.class, Boolean.TYPE, Boolean.TYPE));
			}
		}
		
		/*
		 * Get the regular haptic feedback method
		 */
		mMethods.put("performHapticFeedback", pwm.findMethodDeep("performHapticFeedbackLw", Match.BEST, "android.view.WindowManagerPolicy$WindowState", Integer.TYPE, Boolean.TYPE));
		
		/*
		 * Locate KeyGuard Tools
		 */
		mKeyguardMediator = pwm.findFieldDeep( SDK.MANAGER_KEYGUARD_VERSION > 1 ? "mKeyguardDelegate" : "mKeyguardMediator" ).getValueToInstance();
		mKeyguardMediator.setOnErrorListener(new OnErrorListener(){
			@Override
			public void onError(ReflectMember<?> member) {
				member.getReflectClass().setReceiver(
						mPhoneWindowManager.findField( SDK.MANAGER_KEYGUARD_VERSION > 1 ? "mKeyguardDelegate" : "mKeyguardMediator" ).getValue()
				);
			}
		});
		
		mMethods.put("KeyguardMediator.isShowing", mKeyguardMediator.findMethodDeep("isShowingAndNotHidden"));
		mMethods.put("KeyguardMediator.isLocked", mKeyguardMediator.findMethodDeep("isShowing"));
		mMethods.put("KeyguardMediator.isRestricted", mKeyguardMediator.findMethodDeep("isInputRestricted"));
		mMethods.put("KeyguardMediator.dismiss", mKeyguardMediator.findMethodDeep("keyguardDone", Match.DEFAULT, Boolean.TYPE, Boolean.TYPE));
		
		/*
		 * Get the Activity Management tools
		 */
		mActivityManager = ReflectClass.forReceiver(((Context) mContext.getReceiver()).getSystemService(Context.ACTIVITY_SERVICE));
		mActivityManagerService = ReflectClass.forName("android.app.ActivityManagerNative").findMethod("getDefault").invokeToInstance();
		
		/*
		 * Get the Power Management tools
		 */
		mPowerManager = ReflectClass.forReceiver(((Context) mContext.getReceiver()).getSystemService(Context.POWER_SERVICE));
		mPowerManagerService = mPowerManager.findField("mService").getValueToInstance();
		
		if (SDK.MANAGER_POWER_VERSION == 1) {
			mMethods.put("forceUserActivityLocked", mPowerManagerService.findMethodDeep("forceUserActivityLocked"));
		}
		
		/*
		 * Get Input Injection tools
		 */
		mWindowManagerService = pwm.findFieldDeep("mWindowManager").getValueToInstance();
		
		if (SDK.MANAGER_HARDWAREINPUT_VERSION > 1) {
			mInputManager = ReflectClass.forName("android.hardware.input.InputManager").findMethod("getInstance").invokeForReceiver();
			mMethods.put("injectInputEvent", mInputManager.findMethodDeep("injectInputEvent", Match.DEFAULT, KeyEvent.class, Integer.TYPE));
		
		} else {
			mMethods.put("injectInputEvent", mWindowManagerService.findMethodDeep("injectInputEventNoWait", Match.DEFAULT, KeyEvent.class));
		}
		
		/*
		 * Get a hidden method to check internal/external state of devices
		 */
		if (SDK.INPUT_DEVICESTORAGE_VERSION > 1) {
			mMethods.put("isDeviceExternal", ReflectClass.forName("android.view.InputDevice").findMethod("isExternal"));
		}
		
		/*
		 * Get Audio tools
		 */
		mAudioManager = ReflectClass.forReceiver(((Context) mContext.getReceiver()).getSystemService(Context.AUDIO_SERVICE));
		
		mReady = true;
	}
	
	public Boolean isReady() {
		return mReady;
	}
	
	/*
	 * DOTO: Make a cache based on deviceId. To much IPC communication in this one.
	 */
	public Boolean validateDeviceType(Object event) {
		/*
		 * Gingerbread has no access to the KeyEvent in the intercept method.
		 * Instead we parse the keycode on these versions and skip the first check here. 
		 */
		KeyEvent keyEvent = event instanceof KeyEvent ? (KeyEvent) event : null;
		Integer keyCode = event instanceof KeyEvent ? keyEvent.getKeyCode() : (Integer) event;
		
		if (keyEvent != null && keyEvent.getDeviceId() != -1) {
			Integer source = keyEvent.getSource();
			InputDevice device = keyEvent.getDevice();
			
			/*
			 * We do not want to handle regular Keyboards or gaming devices. 
			 * Do not trust KeyCharacterMap.getKeyboardType() as it can easily display anything
			 * as a FULL PC Keyboard. InputDevice.getKeyboardType() should be safer. 
			 */
			if ((device != null && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) || 
					(source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
					(source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD || 
					(source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
				
				return false;
			}
		}
		
		/*
		 * Now that we know that the device type is supported, let's see if we should handle external once.
		 */
		if (!mXServiceManager.getBoolean(Common.Index.bool.key.remapAllowExternals, Common.Index.bool.value.remapAllowExternals)) {
			if (SDK.INPUT_DEVICESTORAGE_VERSION > 1) {
				InputDevice device = keyEvent.getDevice();
				
				try {
					/*
					 * @Google get a grip, this method should be publicly accessible. Makes no sense to hide it.
					 */
					return device == null || (Boolean) mMethods.get("isDeviceExternal").invokeReceiver(device);
					
				} catch (ReflectException e) { 
					Log.e(TAG, e.getMessage(), e);
				}
				
			} else {
				return KeyCharacterMap.deviceHasKey(keyCode);
			}
		}
		
		return true;
	}
	
	@SuppressLint("NewApi")
	protected void injectInputEvent(Object event, Integer action, Long downTime, Long eventTime, Integer repeatCount, Integer flags) {
		synchronized(PhoneWindowManager.class) {
			KeyEvent keyEvent = null;
			Integer[] actions = action == KeyEvent.ACTION_MULTIPLE ? new Integer[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP} : new Integer[]{action};
			Long time = SystemClock.uptimeMillis();
			
			if (downTime == 0L)
				downTime = time;
			
			if (eventTime == 0L)
				eventTime = time;
			
			if ((flags & KeyEvent.FLAG_FROM_SYSTEM) == 0) 
				flags |= KeyEvent.FLAG_FROM_SYSTEM;
			
			if ((flags & ORIGINAL.FLAG_INJECTED) == 0) 
				flags |= ORIGINAL.FLAG_INJECTED;
			
			if ((flags & KeyEvent.FLAG_LONG_PRESS) == 0 && repeatCount == 1) 
				flags |= KeyEvent.FLAG_LONG_PRESS;
			
			if ((flags & KeyEvent.FLAG_LONG_PRESS) != 0 && repeatCount != 1) 
				flags &= ~KeyEvent.FLAG_LONG_PRESS;
			
			if (event instanceof KeyEvent) {
				keyEvent = KeyEvent.changeTimeRepeat((KeyEvent) event, eventTime, repeatCount, flags);
				
			} else {
				keyEvent = new KeyEvent(downTime, eventTime, actions[0], (Integer) event, repeatCount, 0, (SDK.INPUT_CHARACTERMAP_VERSION > 1 ? KeyCharacterMap.VIRTUAL_KEYBOARD : 0), 0, flags, InputDevice.SOURCE_KEYBOARD);
			}
			
			for (int i=0; i < actions.length; i++) {
				/*
				 * This is for when we have both an up and down event. 
				 */
				if (keyEvent.getAction() != actions[i]) {
					keyEvent = KeyEvent.changeAction(keyEvent, actions[i]);
				}
				
				try {
					if (SDK.MANAGER_HARDWAREINPUT_VERSION > 1) {
						mMethods.get("injectInputEvent").invoke(keyEvent, ORIGINAL.INPUT_MODE_ASYNC);
						
					} else {
						mMethods.get("injectInputEvent").invoke(keyEvent);
					}
					
				} catch (ReflectException e) {
					Log.e(TAG, e.getMessage(), e);
				}	
			}
		}
	}

	protected void performHapticFeedback(KeyEvent keyEvent, Integer type, Integer policyFlags) {
		try {
			if (type == HapticFeedbackConstants.VIRTUAL_KEY) {
				if (SDK.SAMSUNG_FEEDBACK_VERSION == 1) {
					mMethods.get("samsung.performSystemKeyFeedback").invokeOriginal(keyEvent);
					
				} else if (SDK.SAMSUNG_FEEDBACK_VERSION == 2) {
					mMethods.get("samsung.performSystemKeyFeedback").invokeOriginal(keyEvent, false, true);
					
				} else if ((policyFlags & ORIGINAL.FLAG_VIRTUAL) == 0) {
					return;
				}
			}
			
			mMethods.get("performHapticFeedback").invokeOriginal(null, type, false);
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}
	
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
	protected void pokeUserActivity(Long time, Boolean forced) {
		if (forced) {
			if (SDK.MANAGER_POWER_VERSION > 1) {
				((PowerManager) mPowerManager.getReceiver()).wakeUp(time);
				
			} else {
				/*
				 * API's below 17 does not support PowerManager#wakeUp, so
				 * instead we will trick our way into the hidden IPowerManager#forceUserActivityLocked which 
				 * is not accessible trough the regular PowerManager class. It is the same method that 
				 * turns on the screen when you plug in your USB cable.
				 */
				try {
					mMethods.get("forceUserActivityLocked").invoke();
					
				} catch (ReflectException e) {
					Log.e(TAG, e.getMessage(), e);
				}
			}
			
		} else {
			((PowerManager) mPowerManager.getReceiver()).userActivity(time, true);
		}
	}
	
	@SuppressLint("NewApi")
	protected void changeDisplayState(Long time, Boolean on) {
		if (on) {
			pokeUserActivity(time, true);
			
		} else {
			((PowerManager) mPowerManager.getReceiver()).goToSleep(time);
		}
	}
	
	public Boolean isKeyguardShowing() {
		try {
			return (Boolean) mMethods.get("KeyguardMediator.isShowing").invoke();
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		
		return false;
	}
	
	public Boolean isKeyguardLockedAndInsecure() {
		if (isKeyguardLocked()) {
			try {
				return !((Boolean) mMethods.get("KeyguardMediator.isRestricted").invoke());
				
			} catch (ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
		
		return false;
	}
	
	public Boolean isKeyguardLocked() {
		try {
			return (Boolean) mMethods.get("KeyguardMediator.isLocked").invoke();
			
		} catch (ReflectException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		
		return false;
	}
	
	protected void keyGuardDismiss() {
		if (isKeyguardLocked()) {
			try {
				mMethods.get("KeyguardMediator.dismiss").invoke(false, true);
				
			} catch (ReflectException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
	}
	
	public String getCurrentPackageName() {
		List<ActivityManager.RunningTaskInfo> packages = ((ActivityManager) mActivityManager.getReceiver()).getRunningTasks(1);
		
		return packages.size() > 0 ? packages.get(0).baseActivity.getPackageName() : null;
	}
	
	public Boolean invokeCallButton() {
		Integer mode = ((AudioManager) mAudioManager.getReceiver()).getMode();
		Integer callCode = 0;
		
		if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
			callCode = KeyEvent.KEYCODE_ENDCALL;
			
		} else if (mode == AudioManager.MODE_RINGTONE) {
			callCode = KeyEvent.KEYCODE_CALL;
		}
		
		if (callCode > 0) {
			injectInputEvent(callCode, KeyEvent.ACTION_MULTIPLE, 0L, 0L, 0, 0); return true;
		}
		
		return false;
	}
	
	protected void handleKeyAction(final String action, final ActionType actionType, final Integer keyCode, final Long downTime, final Integer flags, final Integer policyFlags, final Boolean isScreenOn) {
		/*
		 * We handle display on here, because some devices has issues
		 * when executing handlers while in deep sleep. 
		 * Some times they will need a few key presses before reacting. 
		 */
		if (!isScreenOn && ((action != null && action.equals("" + KeyEvent.KEYCODE_POWER)) || (action == null && (policyFlags & ORIGINAL.FLAG_WAKE) != 0))) {
			changeDisplayState(downTime, true); return;
		}
		
		if (actionType == ActionType.PRESS) {
			performHapticFeedback(null, HapticFeedbackConstants.LONG_PRESS, policyFlags);
		}
		
		/*
		 * This should always be wrapped and sent to a handler. 
		 * If this is executed directly, some of the actions will crash with the error 
		 * -> 'Can't create handler inside thread that has not called Looper.prepare()'
		 */
		mHandler.post(new Runnable() {
			public void run() {
				String type = Common.actionType(action);
				
				if ("launcher".equals(type)) {
					// TODO: Add this part
					
				} else if ("custom".equals(type)) {
					// TODO: Add this part
					
				} else {
					injectInputEvent(action == null ? keyCode : Integer.parseInt(action), KeyEvent.ACTION_MULTIPLE, downTime, 0L, 0, flags);
				}
			}
		});
	}
}