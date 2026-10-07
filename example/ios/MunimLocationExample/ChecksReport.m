//
//  ChecksReport.m
//  MunimLocationExample
//
//  Writes the "Run checks" report to Documents/munim-location-checks.json so
//  it can be copied off a device with `devicectl device copy from`.
//

#import <React/RCTBridgeModule.h>

@interface ChecksReport : NSObject <RCTBridgeModule>
@end

@implementation ChecksReport

RCT_EXPORT_MODULE()

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

RCT_EXPORT_METHOD(write:(NSString *)json
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  NSURL *directory = [[NSFileManager defaultManager] URLsForDirectory:NSDocumentDirectory
                                                            inDomains:NSUserDomainMask].firstObject;
  NSURL *url = [directory URLByAppendingPathComponent:@"munim-location-checks.json"];
  NSError *error = nil;
  [json writeToURL:url atomically:YES encoding:NSUTF8StringEncoding error:&error];
  if (error) {
    reject(@"E_WRITE", error.localizedDescription, error);
    return;
  }
  NSLog(@"MUNIM_LOCATION_CHECKS written to %@", url.path);
  resolve(url.path);
}

RCT_EXPORT_METHOD(logBackground:(NSString *)message)
{
  NSLog(@"MUNIM_LOCATION_BG %@", message);
}

@end
