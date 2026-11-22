-- L9-4 库存预扣脚本：判断和扣减在一次 Lua 里原子完成
-- KEYS[1] = mall:stock:{productId}
-- ARGV[1] = 扣减数量
-- 返回：>=0 剩余库存；-1 库存不足；-2 key 不存在
local stock = redis.call('get', KEYS[1])
if not stock then
    return -2
end

local num = tonumber(ARGV[1])
stock = tonumber(stock)
if stock < num then
    return -1
end

redis.call('decrby', KEYS[1], num)
return stock - num
