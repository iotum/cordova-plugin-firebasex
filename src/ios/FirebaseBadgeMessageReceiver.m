#import "FirebaseBadgeMessageReceiver.h"
#import <UIKit/UIKit.h>

@implementation FirebaseBadgeMessageReceiver

- (bool)sendNotification:(NSDictionary *)userInfo {
    NSString *payloadString = userInfo[@"payload"];
    if (!payloadString || ![payloadString isKindOfClass:[NSString class]]) {
        return false;
    }

    NSData *jsonData = [payloadString dataUsingEncoding:NSUTF8StringEncoding];
    if (jsonData == nil) {
        return false;
    }

    NSError *error = nil;
    id parsed = [NSJSONSerialization JSONObjectWithData:jsonData options:0 error:&error];
    if (error || !parsed || ![parsed isKindOfClass:[NSDictionary class]]) {
        return false;
    }

    NSDictionary *payload = (NSDictionary *)parsed;
    NSString *type = payload[@"type"];
    BOOL isBadgeUpdate = [@"badge_update" isEqualToString:type];

    NSDictionary *badgeCounts = nil;
    id badgeCountsValue = payload[@"badge_counts"];
    if ([badgeCountsValue isKindOfClass:[NSDictionary class]]) {
        badgeCounts = (NSDictionary *)badgeCountsValue;
    }

    id totalValue = nil;
    if (isBadgeUpdate) {
        totalValue = payload[@"total"];
        if (badgeCounts != nil && ![totalValue respondsToSelector:@selector(intValue)]) {
            totalValue = badgeCounts[@"total"];
        }
    } else if (badgeCounts != nil) {
        totalValue = badgeCounts[@"total"];
    }

    if (!totalValue || ![totalValue respondsToSelector:@selector(intValue)]) {
        return false;
    }

    int total = [totalValue intValue];
    dispatch_async(dispatch_get_main_queue(), ^{
        [[UIApplication sharedApplication] setApplicationIconBadgeNumber:MAX(0, total)];
    });

    return isBadgeUpdate;
}

@end
