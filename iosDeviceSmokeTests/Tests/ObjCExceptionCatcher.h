@import Foundation;

NS_ASSUME_NONNULL_BEGIN

@interface ObjCExceptionCatcher : NSObject

/// Executes `block` inside an ObjC @try/@catch.
/// Returns nil on success, or an NSError wrapping the NSException on failure.
/// The error's userInfo includes NSExceptionCallStackSymbols for debugging.
+ (NSError *_Nullable)catchExceptionInBlock:(void (NS_NOESCAPE ^)(void))block;

@end

NS_ASSUME_NONNULL_END
