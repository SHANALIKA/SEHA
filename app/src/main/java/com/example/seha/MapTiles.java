package com.example.seha;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

/** Shared map tile source (no API key needed). Change it here and all maps update. */
public class MapTiles {

    public static final OnlineTileSourceBase STREET = new OnlineTileSourceBase(
            "EsriWorldStreet", 0, 19, 256, ".png",
            new String[]{"https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"},
            "Tiles © Esri — Source: Esri, HERE, Garmin, OpenStreetMap contributors") {
        @Override
        public String getTileURLString(long pMapTileIndex) {
            // Esri uses {z}/{y}/{x}
            return getBaseUrl()
                    + MapTileIndex.getZoom(pMapTileIndex) + "/"
                    + MapTileIndex.getY(pMapTileIndex) + "/"
                    + MapTileIndex.getX(pMapTileIndex);
        }
    };
}