#import "FirebaseBadgeMessageReceiver.h"
#import <UIKit/UIKit.h>

@implementation FirebaseBadgeMessageReceiver

static NSString *const kLastBadgeTimestampKey = @"FirebaseBadgeMessageReceiver_lastBadgeTimestampMs";

/**
 * Extracts the badge timestamp_ms from the payload. Looks for it at the top
 * level first (for dedicated badge_update messages) then inside badge_counts
 * (for piggyback badge data on other payload types).
 */
- (long long)badgeTimestampMsFromPayload:(NSDictionary *)payload type:(NSString *)type {
    if ([@"badge_update" isEqualToString:type]) {
        id tsValue = payload[@"timestamp_ms"];
        if ([tsValue respondsToSelector:@selector(longLongValue)]) {
            long long ts = [tsValue longLongValue];
            if (ts > 0) return ts;
        }
    }

    id badgeCountsValue = payload[@"badge_counts"];
    if ([badgeCountsValue isKindOfClass:[NSDictionary class]]) {
        NSDictionary *badgeCounts = (NSDictionary *)badgeCountsValue;
        id tsValue = badgeCounts[@"timestamp_ms"];
        if ([tsValue respondsToSelector:@selector(longLongValue)]) {
            long long ts = [tsValue longLongValue];
            if (ts > 0) return ts;
        }
    }

    return 0;
}

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

    // Check timestamp to avoid processing out-of-order badge updates
    long long timestampMs = [self badgeTimestampMsFromPayload:payload type:type];
    @synchronized ([FirebaseBadgeMessageReceiver class]) {
        if (timestampMs > 0) {
            long long lastTimestamp = 0;
            NSNumber *stored = [[NSUserDefaults standardUserDefaults] objectForKey:kLastBadgeTimestampKey];
            if (stored != nil) {
                lastTimestamp = [stored longLongValue];
            }
            if (timestampMs <= lastTimestamp) {
                NSLog(@"FirebaseBadgeMessageReceiver: Skipping stale badge update: timestamp_ms=%lld <= lastProcessed=%lld",
                      timestampMs, lastTimestamp);
                return isBadgeUpdate;
            }
            [[NSUserDefaults standardUserDefaults] setObject:[NSNumber numberWithLongLong:timestampMs] forKey:kLastBadgeTimestampKey];
        }
    }

    int total = [totalValue intValue];
    dispatch_async(dispatch_get_main_queue(), ^{
        [[UIApplication sharedApplication] setApplicationIconBadgeNumber:MAX(0, total)];
    });

    return isBadgeUpdate;
}

@end
