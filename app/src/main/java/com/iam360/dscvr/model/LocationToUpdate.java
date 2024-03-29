package com.iam360.dscvr.model;

/**
 * Created by Mariel on 9/15/2016.
 */
public class LocationToUpdate {
    final double latitude;
    final double longitude;
    final String text;
    final String country;
    final String country_short;
    final String place;
    final String region;
    final boolean poi;

    public LocationToUpdate(double latitude, double longitude, String text, String country, String country_short,
                            String place, String region, boolean poi) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.text = text;
        this.country = country;
        this.country_short = country_short;
        this.place = place;
        this.region = region;
        this.poi = poi;
    }

    public String toString() {
        return "{lat: "+latitude+", lon: "+longitude+", text: "+text+", country: "+country+", country_short: "+
                country_short+", place: "+place+", region: "+region+", poi: "+poi+"}";
    }
}
