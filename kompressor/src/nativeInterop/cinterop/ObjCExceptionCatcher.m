#import "ObjCExceptionCatcher.h"

AVAssetWriterInput* _Nullable KMP_safeCreateWriterInput(
    NSString* _Nonnull mediaType,
    NSDictionary* _Nullable outputSettings
) {
    @try {
        return [[AVAssetWriterInput alloc] initWithMediaType:mediaType
                                             outputSettings:outputSettings];
    } @catch (NSException *exception) {
        NSLog(@"[Kompressor] AVAssetWriterInput threw NSException: name=%@ reason=%@",
              exception.name, exception.reason);
        return nil;
    }
}
