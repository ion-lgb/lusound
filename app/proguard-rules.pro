# Media3, Room and Coil publish their consumer rules.

# Opencc4j 1.14.0 default bootstrap uses Heaven Instances.singleton(Class.newInstance).
# Keep the no-arg constructors of its default segment, data map and four dictionaries.
-keep,allowobfuscation class com.github.houbb.opencc4j.support.segment.impl.FastForwardSegment { public <init>(); }
-keep,allowobfuscation class com.github.houbb.opencc4j.support.datamap.impl.DataMapDefault { public <init>(); }
-keep,allowobfuscation class com.github.houbb.opencc4j.support.data.impl.STCharData { public <init>(); }
-keep,allowobfuscation class com.github.houbb.opencc4j.support.data.impl.TSCharData { public <init>(); }
-keep,allowobfuscation class com.github.houbb.opencc4j.support.data.impl.STPhraseData { public <init>(); }
-keep,allowobfuscation class com.github.houbb.opencc4j.support.data.impl.TSPhraseData { public <init>(); }
