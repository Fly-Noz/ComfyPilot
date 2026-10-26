# 列出可用于手机连接的 IPv4：输出 "网卡名|IP"（跳过回环与 169.254 自动地址）。
$items = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' } |
    Sort-Object InterfaceIndex
foreach ($item in $items) {
    "{0}|{1}" -f $item.InterfaceAlias, $item.IPAddress
}