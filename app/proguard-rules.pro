# Add project specific ProGuard rules here.
# Keep AIDL-generated Stub classes intact.
-keep class de.lobianco.saftssh.rustdesk.IRustDeskSessionService { *; }
-keep class de.lobianco.saftssh.rustdesk.IRustDeskSessionService$Stub { *; }
-keep class de.lobianco.saftssh.rustdesk.IRustDeskSession { *; }
-keep class de.lobianco.saftssh.rustdesk.IRustDeskSession$Stub { *; }
-keep class de.lobianco.saftssh.rustdesk.IRustDeskSessionCallback { *; }
-keep class de.lobianco.saftssh.rustdesk.IRustDeskSessionCallback$Stub { *; }

# Keep JNI-called native bridge methods (see NativeBridge.kt / rust/android-shim).
-keep class de.lobianco.saftssh.rustdesk.NativeBridge { *; }
