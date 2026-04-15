#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>

AVAssetWriterInput* _Nullable KMP_safeCreateWriterInput(
    NSString* _Nonnull mediaType,
    NSDictionary* _Nullable outputSettings
);
