#import "ObjCExceptionCatcher.h"

@implementation ObjCExceptionCatcher

+ (NSError *_Nullable)catchExceptionInBlock:(void (NS_NOESCAPE ^)(void))block {
    @try {
        block();
        return nil;
    } @catch (NSException *exception) {
        return [NSError errorWithDomain:@"NSExceptionDomain"
                                   code:0
                               userInfo:@{
            NSLocalizedDescriptionKey: exception.reason ?: @"Unknown NSException",
            @"NSExceptionName": exception.name ?: @"",
            @"NSExceptionCallStackSymbols": exception.callStackSymbols ?: @[],
        }];
    }
}

@end
