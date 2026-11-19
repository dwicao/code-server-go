package org.json;
/** Minimal shim of org.json so Server can be unit-tested without the Android jar. */
public class JSONException extends Exception {
    public JSONException(String message) { super(message); }
}
