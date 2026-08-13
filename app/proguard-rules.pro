# Shizuku's UserService mechanism instantiates this class by name via raw
# reflection (Class.newInstance(), from moe.shizuku.starter.ServiceStarter,
# running in its own separate process) — completely invisible to R8's static
# call-graph analysis, since nothing in this app's own code ever writes
# `WindowTilingServiceImpl()` directly. Without this rule R8 renames/strips
# it in release builds, and the reflective instantiation fails at runtime
# with "InstantiationException: java.lang.Class<X> cannot be instantiated"
# — Shizuku permission still shows granted, bindUserService() still gets
# called, but the service process never actually starts. Debug builds never
# hit this since minification is off there.
-keep class dev.lutergs.android_wm.service.WindowTilingServiceImpl { *; }

# The AIDL-generated interface/Stub it implements and the client casts to.
-keep class dev.lutergs.android_wm.IWindowTilingService { *; }
-keep class dev.lutergs.android_wm.IWindowTilingService$* { *; }
