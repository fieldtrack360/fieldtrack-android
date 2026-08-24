# Tracker geo build-time R8 rules.
#
# Only types used by the sample or another independently published Tracker module retain
# their integration names. Other public declarations remain but may be renamed. Internal
# implementation is shrunk, optimized, and repackaged under `tr.dev.geo`.

-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Stable data and extension seams shared by core, maps, snap, sync, and the sample.
# Serializer implementations are omitted so only their public companion entrypoints
# retain names.
-keep public class com.field360.traker.geo.model.ActivityType { public protected *; }
-keep public class com.field360.traker.geo.model.Bounds { public protected *; }
-keep public class com.field360.traker.geo.model.Bounds$Companion { public protected *; }
-keep public class com.field360.traker.geo.model.FilterState { public protected *; }
-keep public class com.field360.traker.geo.model.FilterState$Companion { public protected *; }
-keep public class com.field360.traker.geo.model.FixDecision { public protected *; }
-keep public class com.field360.traker.geo.model.GeoPoint { public protected *; }
-keep public class com.field360.traker.geo.model.IngestContext { public protected *; }
-keep public class com.field360.traker.geo.model.IngestContext$Companion { public protected *; }
-keep public class com.field360.traker.geo.model.MockPolicy { public protected *; }
-keep public class com.field360.traker.geo.model.MotionState { public protected *; }
-keep public class com.field360.traker.geo.model.MovementStatus { public protected *; }
-keep public class com.field360.traker.geo.model.PipelineResult { public protected *; }
-keep public class com.field360.traker.geo.model.ProviderSnapshot { public protected *; }
-keep public class com.field360.traker.geo.model.ProviderSnapshot$Companion { public protected *; }
-keep public class com.field360.traker.geo.model.Reasons { public protected *; }
-keep public class com.field360.traker.geo.model.TrackFix { public protected *; }
-keep public class com.field360.traker.geo.model.TrackFix$Companion { public protected *; }
-keep public class com.field360.traker.geo.model.TrackPoint { public protected *; }
-keep public class com.field360.traker.geo.model.Verdict { public protected *; }
-keep public class com.field360.traker.geo.model.Verdict$* { public protected *; }
-keep public class com.field360.traker.geo.port.** { public protected *; }
-keep public class com.field360.traker.geo.plot.model.ArrowAnchor { public protected *; }
-keep public class com.field360.traker.geo.plot.model.ArrowAnchor$Companion { public protected *; }
-keep public class com.field360.traker.geo.plot.model.LiveTrackUpdate { public protected *; }
-keep public class com.field360.traker.geo.plot.model.PlotPoint { public protected *; }
-keep public class com.field360.traker.geo.plot.model.PuckState { public protected *; }
-keep public class com.field360.traker.geo.plot.model.PuckState$Companion { public protected *; }
-keep public class com.field360.traker.geo.plot.model.RenderTag { public protected *; }
-keep public class com.field360.traker.geo.plot.model.SegmentType { public protected *; }
-keep public class com.field360.traker.geo.plot.model.Smoothing { public protected *; }
-keep public class com.field360.traker.geo.plot.model.Smoothing$Companion { public protected *; }
-keep public class com.field360.traker.geo.plot.model.StopNode { public protected *; }
-keep public class com.field360.traker.geo.plot.model.StopNode$Companion { public protected *; }
-keep public class com.field360.traker.geo.plot.model.Track { public protected *; }
-keep public class com.field360.traker.geo.plot.model.Track$Companion { public protected *; }
-keep public class com.field360.traker.geo.plot.model.TrackJsonPoint { public protected *; }
-keep public class com.field360.traker.geo.plot.model.TrackJsonPoint$Companion { public protected *; }
-keep public class com.field360.traker.geo.plot.model.TrackOptions { public protected *; }
-keep public class com.field360.traker.geo.plot.model.TrackOptions$Companion { public protected *; }
-keep public class com.field360.traker.geo.plot.model.TrackSegment { public protected *; }
-keep public class com.field360.traker.geo.plot.model.TrackSegment$Companion { public protected *; }
-keep public class com.field360.traker.geo.plot.model.TrackStats { public protected *; }
-keep public class com.field360.traker.geo.plot.model.TrackStats$Companion { public protected *; }

# Stable algorithm entry points referenced across separately compiled artifacts.
-keep public class com.field360.traker.geo.export.GeoJson { public protected *; }
-keep public class com.field360.traker.geo.export.TrackJson { public protected *; }
-keep public class com.field360.traker.geo.filter.AcceptancePipeline { public protected *; }
-keep public class com.field360.traker.geo.filter.ClockGuard { public protected *; }
-keep public class com.field360.traker.geo.filter.ClockGuard$Step { public protected *; }
-keep public class com.field360.traker.geo.filter.TrackerConstants { public protected *; }
-keep public class com.field360.traker.geo.filter.TrackerConstants$Companion { public protected *; }
-keep public class com.field360.traker.geo.math.Bearing { public protected *; }
-keep public class com.field360.traker.geo.math.Geodesy { public protected *; }
-keep public class com.field360.traker.geo.math.Geodesy$Projection { public protected *; }
-keep public class com.field360.traker.geo.math.Haversine { public protected *; }
-keep public class com.field360.traker.geo.motion.MotionEvent { public protected *; }
-keep public class com.field360.traker.geo.motion.MotionEvent$AcceptedFix { public protected *; }
-keep public class com.field360.traker.geo.motion.MotionEvent$ActivityEnter { public protected *; }
-keep public class com.field360.traker.geo.motion.MotionEvent$ChangePace { public protected *; }
-keep public class com.field360.traker.geo.motion.MotionEvent$SignificantMotion { public protected *; }
-keep public class com.field360.traker.geo.motion.MotionEvent$StationaryFenceExit { public protected *; }
-keep public class com.field360.traker.geo.motion.MotionEvent$Tick { public protected *; }
-keep public class com.field360.traker.geo.motion.MotionStateMachine { public protected *; }
-keep public class com.field360.traker.geo.motion.MotionStateMachine$Companion { public protected *; }
-keep public class com.field360.traker.geo.motion.MotionStateMachine$State { public protected *; }
-keep public class com.field360.traker.geo.motion.MotionStateMachine$Transition { public protected *; }
-keep public class com.field360.traker.geo.motion.TurnDetector { public protected *; }
-keep public class com.field360.traker.geo.motion.TurnDetector$Companion { public protected *; }
-keep public class com.field360.traker.geo.motion.TurnDetector$Result { public protected *; }
-keep public class com.field360.traker.geo.motion.TurnDetector$State { public protected *; }
-keep public class com.field360.traker.geo.plot.LiveTrackEngine { public protected *; }
-keep public class com.field360.traker.geo.plot.PolylineCodec { public protected *; }
-keep public class com.field360.traker.geo.plot.PolylineCodec$Decoder { public protected *; }
-keep public class com.field360.traker.geo.plot.PolylineCodec$Encoder { public protected *; }
-keep public class com.field360.traker.geo.plot.PuckAnimation { public protected *; }
-keep public class com.field360.traker.geo.plot.RouteSnap { public protected *; }
-keep public class com.field360.traker.geo.plot.RouteSnap$Snapped { public protected *; }
-keep public class com.field360.traker.geo.plot.RouteSnap$Tracker { public protected *; }
-keep public class com.field360.traker.geo.plot.RouteSnap$Tracker$Companion { public protected *; }
-keep public class com.field360.traker.geo.plot.Snapper { public protected *; }
-keep public class com.field360.traker.geo.plot.Snapper$* { public protected *; }
-keep public class com.field360.traker.geo.plot.TrackBuilder { public protected *; }
-keep public class com.field360.traker.geo.util.Uuids { public protected *; }

# Persisted and serialized enum values are data, not implementation names.
-keepclassmembers,allowoptimization enum com.field360.traker.geo.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-repackageclasses 'tr.dev.geo'
