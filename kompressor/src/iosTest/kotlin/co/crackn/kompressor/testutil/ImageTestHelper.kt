@file:OptIn(ExperimentalForeignApi::class)

package co.crackn.kompressor.testutil

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSURL
import platform.Foundation.writeToURL
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIRectFill

/** Creates a test PNG image with a blue background and a red rectangle in the top-left quarter. */
fun createTestImage(testDir: String, width: Int, height: Int): String {
    UIGraphicsBeginImageContextWithOptions(
        CGSizeMake(width.toDouble(), height.toDouble()), true, 1.0,
    )
    try {
        UIColor.blueColor.setFill()
        UIRectFill(CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
        UIColor.redColor.setFill()
        UIRectFill(CGRectMake(0.0, 0.0, width / 2.0, height / 2.0))
        val image = UIGraphicsGetImageFromCurrentImageContext()!!
        val data = checkNotNull(UIImagePNGRepresentation(image)) { "PNG encoding failed" }

        val path = testDir + "input_${width}x$height.png"
        val url = NSURL.fileURLWithPath(path)
        check(data.writeToURL(url, atomically = true)) { "Failed to write PNG: $path" }
        return path
    } finally {
        UIGraphicsEndImageContext()
    }
}
