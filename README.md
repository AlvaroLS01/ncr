# NCR Self-Checkout integration with comerzzia POS


## Auto Sign-On bypass

The POS can force the login flow to continue even if the SCO does not send the `SignOn` message. This behaviour is configurable in `NCRPosConfiguration.xml`:

```
<forceAutoSignOn>true</forceAutoSignOn>
<autoSignOnTimeoutMs>1200</autoSignOnTimeoutMs>
<defaultUserIdWhenMissing>0</defaultUserIdWhenMissing>
<defaultPasswordWhenMissing>1</defaultPasswordWhenMissing>
<defaultRoleWhenMissing>Operator</defaultRoleWhenMissing>
<defaultLaneNumber>27</defaultLaneNumber>
<defaultMenuUid>MENU_POR_DEFECTO</defaultMenuUid>
```

Set `forceAutoSignOn` to `false` to disable the bypass.

