package br.com.hadryan.duo.finance.config;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class LoggingAspect {

    @Pointcut("within(br.com.hadryan.duo.finance..*Controller)")
    public void controllers() {}

    @Around("controllers()")
    public Object log(ProceedingJoinPoint pjp) throws Throwable {
        String method = pjp.getTarget().getClass().getSimpleName()
                + "." + pjp.getSignature().getName();

        long start = System.currentTimeMillis();
        try {
            Object result  = pjp.proceed();
            long   elapsed = System.currentTimeMillis() - start;
            log.info("Request finalizado | método={} | tempo={}ms", method, elapsed);
            return result;
        } catch (Throwable ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Erro no endpoint | método={} | tempo={}ms | erro={}",
                    method, elapsed, ex.getMessage());
            throw ex;
        }
    }
}