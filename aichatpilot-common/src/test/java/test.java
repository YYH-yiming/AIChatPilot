import com.yyh.common.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

public class test {

    @Test
    void testJWT(){
        Long userId = 100L;
        Long tenantId = 110L;

        String testJWT = JwtUtil.generateToken(userId, tenantId);
        System.out.println(testJWT);
        Claims claims = JwtUtil.parseToken(testJWT);
        System.out.println(claims.toString());
    }
}
