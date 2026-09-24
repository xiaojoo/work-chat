package middleware

import (
	"errors"
	"strconv"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// MintInternalToken 给服务间调用签发短期令牌。服务侧只认签名和 exp，
// 不再相信任何人自报的 X-User-Id —— 身份来自这里，而这里来自 WS 握手时验过的 JWT。
func MintInternalToken(secret string, userID int64, ttl time.Duration) (string, error) {
	if secret == "" {
		return "", errors.New("internal jwt secret is not configured")
	}
	now := time.Now()
	claims := jwt.RegisteredClaims{
		Subject:   strconv.FormatInt(userID, 10),
		Issuer:    "chat-gateway",
		IssuedAt:  jwt.NewNumericDate(now),
		ExpiresAt: jwt.NewNumericDate(now.Add(ttl)),
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(secret))
}

func ParseJWT(tokenString, secret string) (int64, error) {
	token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("unexpected signing method")
		}
		return []byte(secret), nil
	})

	if err != nil {
		return 0, err
	}

	if claims, ok := token.Claims.(jwt.MapClaims); ok && token.Valid {
		if exp, ok := claims["exp"].(float64); ok {
			if time.Unix(int64(exp), 0).Before(time.Now()) {
				return 0, errors.New("token expired")
			}
		}

		sub, ok := claims["sub"].(string)
		if !ok {
			return 0, errors.New("invalid subject")
		}

		var userId int64
		for _, c := range sub {
			userId = userId*10 + int64(c-'0')
		}
		return userId, nil
	}

	return 0, errors.New("invalid token")
}
