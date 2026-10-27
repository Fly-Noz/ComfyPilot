# List usable IPv4 addresses as "adapter|ip".
# Skip loopback and 169.254 link-local addresses.
$items = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' } |
    Sort-Object InterfaceIndex
foreach ($item in $items) {
    "{0}|{1}" -f $item.InterfaceAlias, $item.IPAddress
}