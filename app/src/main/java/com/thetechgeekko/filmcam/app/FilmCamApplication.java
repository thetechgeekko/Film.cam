package com.thetechgeekko.filmcam.app;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.util.Log;
import java.lang.reflect.Field;

/**
 * Modern Application class for Film.cam
 * Extends legacy PhotonCamera to maintain compatibility with legacy static calls
 */
public class FilmCamApplication extends PhotonCamera {
    
    @Override
    public void onCreate() {
        // Initialize legacy singleton to prevent NullPointerException in legacy code
        // Since we are the Application instance, we set PhotonCamera.sPhotonCamera to ourselves
        try {
            Field field = PhotonCamera.class.getDeclaredField("sPhotonCamera");
            field.setAccessible(true);
            field.set(null, this);
        } catch (Exception e) {
            Log.e("FilmCam", "Failed to bridge legacy PhotonCamera singleton", e);
        }
        
        super.onCreate();
    }
}
