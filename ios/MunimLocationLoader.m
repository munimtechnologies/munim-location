//
//  MunimLocationLoader.m
//  munim-location
//
//  Registers a didFinishLaunching observer before the app delegate runs, so
//  MunimLocationCore can recreate its CLLocationManagers during launch. iOS
//  only delivers the significant-change, visit, and region events that
//  relaunched a terminated app to managers configured early in launch.
//

#import <Foundation/Foundation.h>

extern void munim_location_install_launch_observer(void);

@interface MunimLocationLoader : NSObject
@end

@implementation MunimLocationLoader

+ (void)load
{
  munim_location_install_launch_observer();
}

@end
