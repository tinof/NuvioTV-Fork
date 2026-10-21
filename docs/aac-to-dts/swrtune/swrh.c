// swr harness: raw f32 interleaved stereo in -> fltp -> swr_convert -> fltp -> raw f32 interleaved out
// usage: swrh in_rate out_rate in.f32 out.f32 [name=value ...]
// Prints: delay after priming, timing of swr_convert calls, effective options.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>

static double now(void){ struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return t.tv_sec+t.tv_nsec*1e-9; }

int main(int argc, char **argv){
    if (argc < 5){ fprintf(stderr,"usage\n"); return 1; }
    int ir = atoi(argv[1]), orr = atoi(argv[2]);
    FILE *fi = fopen(argv[3],"rb"); FILE *fo = fopen(argv[4],"wb");
    if(!fi||!fo){perror("open");return 1;}
    fseek(fi,0,SEEK_END); long nbytes=ftell(fi); fseek(fi,0,SEEK_SET);
    long nin = nbytes/8;
    float *inter = malloc(nbytes); if(fread(inter,1,nbytes,fi)!=(size_t)nbytes){return 1;} fclose(fi);
    float *pl[2]; pl[0]=malloc(nin*4); pl[1]=malloc(nin*4);
    for(long i=0;i<nin;i++){pl[0][i]=inter[2*i];pl[1][i]=inter[2*i+1];}
    AVChannelLayout st = AV_CHANNEL_LAYOUT_STEREO;
    SwrContext *s = NULL;
    if (swr_alloc_set_opts2(&s,&st,AV_SAMPLE_FMT_FLTP,orr,&st,AV_SAMPLE_FMT_FLTP,ir,0,NULL)<0) return 2;
    for(int a=5;a<argc;a++){
        char *eq=strchr(argv[a],'='); *eq=0;
        int r = av_opt_set(s,argv[a],eq+1,0);
        if(r<0){fprintf(stderr,"bad option %s=%s (%d)\n",argv[a],eq+1,r);return 3;}
    }
    if(swr_init(s)<0){fprintf(stderr,"swr_init failed\n");return 4;}
    long maxout = (long)((double)nin*orr/ir)+65536;
    float *op[2]; op[0]=malloc(maxout*4); op[1]=malloc(maxout*4);
    long nout=0; const int CH=1024; double t=0; int64_t delay_primed=-1;
    for(long pos=0;pos<nin;pos+=CH){
        int n = (int)((nin-pos)<CH?(nin-pos):CH);
        const uint8_t *ip[2]={(uint8_t*)(pl[0]+pos),(uint8_t*)(pl[1]+pos)};
        uint8_t *o[2]={(uint8_t*)(op[0]+nout),(uint8_t*)(op[1]+nout)};
        double t0=now();
        int r=swr_convert(s,o,(int)(maxout-nout),ip,n);
        t+=now()-t0;
        if(r<0) return 5;
        nout+=r;
        if(pos>=8*CH && delay_primed<0) delay_primed=swr_get_delay(s,orr);
    }
    uint8_t *o[2]={(uint8_t*)(op[0]+nout),(uint8_t*)(op[1]+nout)};
    double t0=now(); int r=swr_convert(s,o,(int)(maxout-nout),NULL,0); t+=now()-t0;
    long nout_noflush=nout; if(r>0) nout+=r;
    int64_t fl=0,pc=0,er=0,li=0; double co=0,kb=0;
    av_opt_get_int(s,"filter_size",0,&fl); av_opt_get_int(s,"phase_shift",0,&pc);
    av_opt_get_int(s,"exact_rational",0,&er); av_opt_get_int(s,"linear_interp",0,&li);
    av_opt_get_double(s,"cutoff",0,&co); av_opt_get_double(s,"kaiser_beta",0,&kb);
    printf("{\"cpu_s\":%.6f,\"nin\":%ld,\"nout\":%ld,\"nout_noflush\":%ld,\"delay_primed_out\":%lld,"
           "\"filter_size\":%lld,\"phase_shift\":%lld,\"exact_rational\":%lld,\"linear_interp\":%lld,\"cutoff\":%g,\"kaiser_beta\":%g}\n",
           t,nin,nout,nout_noflush,(long long)delay_primed,(long long)fl,(long long)pc,(long long)er,(long long)li,co,kb);
    for(long i=0;i<nout;i++){float v[2]={op[0][i],op[1][i]};fwrite(v,4,2,fo);}
    fclose(fo); swr_free(&s); return 0;
}
