#import "ObjCExceptionCatcher.h"

@implementation ObjCExceptionCatcher

+ (BOOL)tryBlock:(void (NS_NOESCAPE ^)(void))block
           error:(NSError *_Nullable *_Nullable)error {
    @try {
        block();
        return YES;
    } @catch (NSException *exception) {
        if (error) {
            *error = [NSError errorWithDomain:@"NSExceptionDomain"
                                         code:0
                                     userInfo:@{
                NSLocalizedDescriptionKey: exception.reason ?: @"Unknown NSException",
                @"NSExceptionName": exception.name ?: @"",
                @"NSExceptionCallStackSymbols": exception.callStackSymbols ?: @[],
            }];
        }
        return NO;
    }
}

@end
