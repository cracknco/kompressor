@import Foundation;

NS_ASSUME_NONNULL_BEGIN

@interface ObjCExceptionCatcher : NSObject

/// Executes `block` inside an ObjC @try/@catch. Returns YES on success.
/// If an NSException is thrown, returns NO and populates `error` with
/// the exception name and reason.
+ (BOOL)tryBlock:(void (NS_NOESCAPE ^)(void))block
           error:(NSError *_Nullable *_Nullable)error;

@end

NS_ASSUME_NONNULL_END
